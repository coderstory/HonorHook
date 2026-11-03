package top.honorhook.tools.ghostlock.shizuku;

import top.honorhook.tools.ghostlock.shizuku.IGhostlockCallback;
import top.honorhook.tools.ghostlock.shizuku.IGhostlockStatusCallback;

interface IGhostlockUserService {
    void destroy() = 16777114;
    void runExploit(int primaryCpu, int consumerCpu, boolean safeMode, boolean forceAttack, in byte[] profileBlob, @nullable String debugDir, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 2;
    void runExploitAsManager(int primaryCpu, int consumerCpu, boolean safeMode, boolean forceAttack, in byte[] profileBlob, @nullable String debugDir, @nullable String managerPackage, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 3;
    void runCve64560(in byte[] fanout, in byte[] rotate, in byte[] su, @nullable String debugDir, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 4;
    /* The 98260 payload is over 2 MiB and cannot fit in one binder transaction,
     * so it is streamed with beginCve98260/appendCve98260 before runCve98260. */
    void beginCve98260() = 5;
    void appendCve98260(in byte[] chunk) = 6;
    void runCve98260(in byte[] su, @nullable String debugDir, @nullable String managerPackage, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 7;
}
