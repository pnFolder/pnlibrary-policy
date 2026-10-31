package pnfolder.policy;

import java.util.Set;
import org.pnfolder.pnlibrary.api.remote.RemotePolicy;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyContext;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyResult;

/** Удалённая политика PnRtp: запрещает запуск только версий из списка BLOCKED, остальным разрешает. */
public final class PnRtpPolicy implements RemotePolicy {

    /** Версии с серьёзной ошибкой. Добавьте сюда версию, и плагин этой версии не запустится. */
    private static final Set<String> BLOCKED = Set.of();

    @Override
    public RemotePolicyResult check(RemotePolicyContext context) {
        String version = context.getProduct().getVersion();
        if (BLOCKED.contains(version)) {
            return RemotePolicyResult.deny("Версия " + version + " PnRtp заблокирована издателем: обновитесь через /pn update.");
        }
        return RemotePolicyResult.allow();
    }
}
