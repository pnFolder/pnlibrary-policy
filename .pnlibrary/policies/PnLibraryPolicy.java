package pnfolder.policy;

import org.pnfolder.pnlibrary.api.remote.RemotePolicy;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyContext;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyResult;

public final class PnLibraryPolicy implements RemotePolicy {

    @Override
    public RemotePolicyResult check(RemotePolicyContext context) {
        return RemotePolicyResult.allow();
    }
}
