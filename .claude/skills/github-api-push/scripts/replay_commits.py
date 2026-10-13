# -*- coding: utf-8 -*-
"""Replay local commits to GitHub via pure REST Git Data API (no git push, optionally no git.exe).

Pipeline per commit: upload blobs (exact git object content) -> create tree -> create commit
(author/committer/dates/message preserved) -> chain parents -> ONE final PATCH of the branch
ref with force=false (non-fast-forward always fails = overwrite fuse).

Modes:
  auto  git.exe usable for LOCAL reads only. Replays every commit in origin/<branch>..HEAD,
        full tree per commit (ls-tree + cat-file), so N commits / deletions / mode bits all
        work; merge commits get linearized (content preserved, second parent dropped).
  files zero-git.exe mode (single commit): explicit file list against worktree content.
        Lines starting with "- " mark deletions. Intermediate commits NOT supported.

Token resolution order: --token / env GH_TOKEN|GITHUB_TOKEN / `gh auth token` /
Windows Credential Manager via sibling extract-gh-cred.ps1 (every candidate verified via
GET /user; token never echoed).

Usage (repo root as cwd):
  python3 -I .claude/skills/github-api-push/scripts/replay_commits.py --mode auto
  python3 -I .claude/skills/github-api-push/scripts/replay_commits.py \
      --mode files --filelist list.txt --message-file msg.txt --author "Name <email>"
"""
import argparse, base64, datetime, json, os, re, shutil, subprocess, sys

API = "https://api.github.com"
HERE = os.path.dirname(os.path.abspath(__file__))


def api(method, path, token, proxy, payload=None, ok=None):
    cmd = ["curl", "-s", "-X", method, "--proxy", proxy, "--max-time", "60",
           "-H", "Accept: application/vnd.github+json",
           "-H", "Authorization: Bearer " + token]
    if payload is not None:
        pf = os.path.join(HERE, "payload.json")
        with open(pf, "w", encoding="ascii") as f:
            json.dump(payload, f, ensure_ascii=True)  # ASCII-only on the wire; Chinese -> \\uXXXX
        cmd += ["-H", "Content-Type: application/json", "--data-binary", "@" + pf]
    cmd.append(API + path)
    r = subprocess.run(cmd, capture_output=True)
    try:
        data = json.loads(r.stdout.decode("utf-8", "replace"))
    except Exception:
        data = {"_raw": r.stdout[:300].decode("utf-8", "replace"),
                "_err": r.stderr.decode("utf-8", "replace")}
    if ok and not ok(data):
        sys.exit("API %s %s failed: %s" % (method, path, json.dumps(data, ensure_ascii=True)[:300]))
    return data


# ---------------- local git access (auto mode only; LOCAL reads, never push) ----------------

def git_text(args, root):
    g = shutil.which("git")
    if not g:
        sys.exit("git.exe not found -- use --mode files for the zero-git path")
    r = subprocess.run([g] + args, cwd=root, capture_output=True)
    if r.returncode != 0:
        sys.exit("git %s failed: %s" % (" ".join(args), r.stderr.decode("utf-8", "replace")))
    return r.stdout.decode("utf-8", "replace")


def git_blob_bytes(sha, root):
    g = shutil.which("git")
    r = subprocess.run([g, "cat-file", "blob", sha], cwd=root, capture_output=True)
    if r.returncode != 0:
        sys.exit("git cat-file %s failed" % sha)
    return r.stdout  # exact object bytes (LF as committed, binary-safe)


def read_ref(root, name):
    """Plain-file ref read (no git.exe): loose ref first, packed-refs fallback."""
    p = os.path.join(root, ".git", *(name.split("/")))
    if os.path.isfile(p):
        return open(p).read().strip()
    pr = os.path.join(root, ".git", "packed-refs")
    if os.path.isfile(pr):
        for line in open(pr):
            if line.strip().endswith(" " + name):
                return line.split()[0]
    return None


# ---------------- token resolution ----------------

def credman_candidates():
    ps1 = os.path.join(HERE, "extract-gh-cred.ps1")
    if not os.path.isfile(ps1):
        return []
    out = subprocess.run(["powershell", "-NoProfile", "-File", ps1],
                         capture_output=True).stdout.decode("utf-8", "replace")
    cands = []
    for line in out.splitlines():
        if not line.startswith("CRED|"):
            continue
        parts = line.split("|")
        # token fields at 5/7 -- 4/6 are the literal "UTF8"/"UTF16" tags (first-run lesson)
        for i in (5, 7):
            if i < len(parts) and parts[i] and all(32 <= ord(c) < 127 for c in parts[i]):
                pref = 0 if parts[1].startswith("GitHub - https://api.github.com") else 1
                cands.append((pref, i == 5, parts[i]))
    cands.sort()
    return [c[2] for c in cands]


def resolve_token(args, proxy):
    if args.token:
        return args.token
    for v in ("GH_TOKEN", "GITHUB_TOKEN"):
        if os.environ.get(v):
            return os.environ[v]
    if shutil.which("gh"):
        r = subprocess.run(["gh", "auth", "token"], capture_output=True)
        t = r.stdout.decode("ascii", "replace").strip()
        if t:
            return t
    for t in credman_candidates():
        if api("GET", "/user", t, proxy).get("login"):
            return t
    sys.exit("no usable token: --token / GH_TOKEN / gh CLI / Windows Credential Manager all failed")


# ---------------- mode: auto (per-commit delta replay; needs git.exe for LOCAL reads) ----------------

def collect_commits(base, tip, root):
    out = git_text(["rev-list", "--reverse", "%s..%s" % (base, tip)], root)
    shas = out.split()
    if not shas:
        sys.exit("nothing to replay (base..tip empty)")
    commits = []
    for sha in shas:
        m = git_text(["log", "-1", "--date=iso-strict",
                      "--format=%an%x1f%ae%x1f%ad%x1f%cn%x1f%ce%x1f%cd%x1f%P%x1f%B", sha], root)
        parts = m.rstrip("\n").split("\x1f")
        if len(parts) < 8:
            sys.exit("bad git log parse for %s" % sha)
        parent = parts[6].split()[0] if parts[6].split() else base
        commits.append({
            "sha": sha,
            "parent": parent,  # first parent (merge commits get linearized, see SKILL.md)
            "author": {"name": parts[0], "email": parts[1], "date": parts[2]},
            "committer": {"name": parts[3], "email": parts[4], "date": parts[5]},
            "message": parts[7].rstrip("\n") or "",
        })
    return commits


def commit_delta(parent_sha, commit_sha, root):
    """Changed entries parent->commit (git diff-tree raw, renames disabled).
    Returns [(status, newmode, newsha, path)]; newmode '160000' = submodule gitlink."""
    out = git_text(["diff-tree", "-r", "--no-renames", "--raw", parent_sha, commit_sha], root)
    delta = []
    for line in out.splitlines():
        if not line.startswith(":"):
            continue
        meta, path = line.split("\t", 1)
        f = meta.lstrip(":").split()  # oldmode newmode oldsha newsha status (5 fields)
        if len(f) < 5:
            continue
        delta.append((f[4], f[1], f[3], path))
    return delta


def replay_auto(base, tip, token, proxy, root, dry, repo):
    commits = collect_commits(base, tip, root)
    print("[3] replaying %d commit(s) (auto mode, delta trees)" % len(commits))
    if dry:
        for c in commits:
            print("    %s %s" % (c["sha"][:10], c["message"].splitlines()[0][:60] if c["message"] else ""))
            for status, newmode, newsha, path in commit_delta(c["parent"], c["sha"], root):
                extra = "  (submodule passthrough)" if newmode == "160000" else ""
                print("        %-3s %s%s" % (status, path, extra))
        return None
    cache = {}
    parent_remote = base
    created = []
    for c in commits:
        base_commit = api("GET", "/repos/%s/git/commits/%s" % (repo, parent_remote), token, proxy,
                          ok=lambda d: d.get("tree", {}).get("sha"))
        entries = []
        for status, newmode, newsha, path in commit_delta(c["parent"], c["sha"], root):
            if status == "D":
                entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
                print("        del  %s" % path)
                continue
            if newmode == "160000":
                # submodule gitlink passthrough: GitHub resolves the sha inside the submodule's
                # own repo; if unreachable the tree POST below fails and we stop honestly.
                entries.append({"path": path, "mode": "160000", "type": "commit", "sha": newsha})
                print("        sub  %s" % path)
                continue
            gh = cache.get(newsha)
            if gh is None:
                b64 = base64.b64encode(git_blob_bytes(newsha, root)).decode("ascii")
                b = api("POST", "/repos/%s/git/blobs" % repo, token, proxy,
                        {"content": b64, "encoding": "base64"}, ok=lambda d: d.get("sha"))
                cache[newsha] = gh = b["sha"]
                print("        blob %s" % path)
            entries.append({"path": path, "mode": newmode, "type": "blob", "sha": gh})
        t = api("POST", "/repos/%s/git/trees" % repo, token, proxy,
                {"base_tree": base_commit["tree"]["sha"], "tree": entries}, ok=lambda d: d.get("sha"))
        cm = api("POST", "/repos/%s/git/commits" % repo, token, proxy,
                 {"message": c["message"], "tree": t["sha"], "parents": [parent_remote],
                  "author": c["author"], "committer": c["committer"]}, ok=lambda d: d.get("sha"))
        parent_remote = cm["sha"]
        created.append(cm)
        print("    commit %s -> %s  %s" % (c["sha"][:10], cm["sha"][:10],
                                           c["message"].splitlines()[0][:50] if c["message"] else ""))
    return created


# ---------------- mode: files (zero-git single commit) ----------------

def files_identity(args, root):
    ts = None
    if args.author:
        m = re.match(r"^(.*?)\s*<([^>]+)>$", args.author.strip())
        if not m:
            sys.exit('--author must look like "Name <email>"')
        name, email = m.group(1), m.group(2)
    else:
        tip = read_ref(root, "refs/heads/" + args.branch)
        name = email = None
        logp = os.path.join(root, ".git", "logs", "HEAD")
        for line in open(logp, encoding="utf-8", errors="replace"):
            if tip and tip in line:
                mm = re.match(r"^([0-9a-f]{40}) ([0-9a-f]{40}) (.+?) <([^>]+)> (\d+) ([+-]\d{4})\t", line)
                if mm:
                    name, email, ts = mm.group(3), mm.group(4), mm.group(5)
                break
        if not email:
            sys.exit('cannot read author from reflog -- pass --author "Name <email>"')
    date = args.date
    if not date and ts:
        date = datetime.datetime.fromtimestamp(int(ts), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if not date:
        date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {"name": name, "email": email, "date": date}


def replay_files(base, token, proxy, args, root, dry):
    if not args.filelist or not (args.message_file or args.message):
        sys.exit("files mode needs --filelist and (--message-file | --message)")
    entries = []
    for line in open(args.filelist, encoding="utf-8"):
        p = line.strip()
        if not p:
            continue
        if p.startswith("- "):
            entries.append({"path": p[2:].strip(), "mode": "100644", "type": "blob", "sha": None})
            continue
        fp = os.path.join(root, p.replace("/", os.sep))
        if not os.path.isfile(fp):
            sys.exit("file in filelist missing on disk: %s" % p)
        if dry:
            entries.append({"path": p, "mode": "100644", "type": "blob", "sha": "?"})
            continue
        b64 = base64.b64encode(open(fp, "rb").read()).decode("ascii")
        b = api("POST", "/repos/%s/git/blobs" % args.repo, token, proxy,
                {"content": b64, "encoding": "base64"}, ok=lambda d: d.get("sha"))
        entries.append({"path": p, "mode": "100644", "type": "blob", "sha": b["sha"]})
        print("    blob ok %s" % p)
    if dry:
        print("[3] dry-run: %d planned tree entries (base_tree delta):" % len(entries))
        for e in entries:
            tag = "DELETE" if e["sha"] is None else "blob"
            print("        %-6s %s" % (tag, e["path"]))
        return None
    base_c = api("GET", "/repos/%s/git/commits/%s" % (args.repo, base), token, proxy,
                 ok=lambda d: d.get("tree", {}).get("sha"))
    t = api("POST", "/repos/%s/git/trees" % args.repo, token, proxy,
            {"base_tree": base_c["tree"]["sha"], "tree": entries}, ok=lambda d: d.get("sha"))
    msg = open(args.message_file, encoding="utf-8").read() if args.message_file else args.message
    ident = files_identity(args, root)
    cm = api("POST", "/repos/%s/git/commits" % args.repo, token, proxy,
             {"message": msg.strip(), "tree": t["sha"], "parents": [base],
              "author": ident, "committer": ident}, ok=lambda d: d.get("sha"))
    print("    commit -> %s  (author %s <%s> %s)" % (cm["sha"][:10], ident["name"], ident["email"], ident["date"]))
    return [cm]


# ---------------- main ----------------

def main():
    # GBK console would mangle Chinese commit subjects in progress output (payloads are
    # unaffected: they go out as ASCII-safe JSON) -- reconfigure so progress lines stay readable.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="coderstory/HonorHook")
    ap.add_argument("--branch", default="master")
    ap.add_argument("--proxy", default="socks5h://127.0.0.1:10808")
    ap.add_argument("--mode", choices=["auto", "files"], default="auto")
    ap.add_argument("--root", default=os.getcwd())
    ap.add_argument("--base", help="override base sha (default: live remote branch head)")
    ap.add_argument("--filelist")
    ap.add_argument("--message-file")
    ap.add_argument("--message", default="")
    ap.add_argument("--author", help='files mode: "Name <email>"')
    ap.add_argument("--date")
    ap.add_argument("--token")
    ap.add_argument("--dry-run", action="store_true", help="no POST at all: verify + enumerate only")
    a = ap.parse_args()
    root = a.root

    token = resolve_token(a, a.proxy)
    user = api("GET", "/user", token, a.proxy, ok=lambda d: d.get("login"))
    print("[1] token owner:", user["login"])

    ref = api("GET", "/repos/%s/git/ref/heads/%s" % (a.repo, a.branch), token, a.proxy,
              ok=lambda d: (d.get("object") or {}).get("sha"))
    base = a.base or ref["object"]["sha"]
    local_base = read_ref(root, "refs/remotes/origin/" + a.branch)
    if local_base and local_base != base:
        print("[!] local origin ref (%s) != live remote (%s) -- using live" % (local_base[:10], base[:10]))
    print("[2] base %s/heads/%s: %s" % (a.repo, a.branch, base[:10]))

    if a.mode == "auto":
        tip = git_text(["rev-parse", "HEAD"], root).strip()
        if tip == base:
            sys.exit("HEAD == base, nothing to replay")
        created = replay_auto(base, tip, token, a.proxy, root, a.dry_run, a.repo)
    else:
        created = replay_files(base, token, a.proxy, a, root, a.dry_run)

    if a.dry_run or created is None:
        print("DRY-RUN end (nothing created)")
        return
    last = created[-1]["sha"]
    r = api("PATCH", "/repos/%s/git/refs/heads/%s" % (a.repo, a.branch), token, a.proxy,
            {"sha": last, "force": False}, ok=lambda d: (d.get("object") or {}).get("sha") == last)
    print("[4] %s/heads/%s -> %s" % (a.repo, a.branch, last[:10]))
    print("DONE https://github.com/%s/commit/%s" % (a.repo, last))
    print("NOTE: remote sha differs from local -- on a git-capable machine run: "
          "git fetch && git reset --hard origin/%s" % a.branch)
    pf = os.path.join(HERE, "payload.json")
    if os.path.isfile(pf):
        os.remove(pf)


if __name__ == "__main__":
    main()
