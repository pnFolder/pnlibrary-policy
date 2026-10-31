package pnfolder.policy;

import java.util.Set;
import org.pnfolder.pnlibrary.api.remote.RemotePolicy;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyContext;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyResult;

/** Удалённая политика PnClans: запрещает запуск только версий из списка BLOCKED, остальным разрешает. */
public final class PnClansPolicy implements RemotePolicy {

    /** Версии с серьёзной ошибкой. Добавьте сюда версию, и плагин этой версии не запустится. */
    private static final Set<String> BLOCKED = Set.of();

    @Override
    public RemotePolicyResult check(RemotePolicyContext context) {
        String version = context.getProduct().getVersion();
        if (BLOCKED.contains(version)) {
            return RemotePolicyResult.deny("Версия " + version + " PnClans заблокирована издателем: обновитесь через /pn update.");
        }
        return RemotePolicyResult.allow();
    }
}
