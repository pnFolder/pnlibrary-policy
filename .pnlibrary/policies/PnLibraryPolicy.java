package pnfolder.policy;

import org.pnfolder.pnlibrary.api.remote.RemotePolicy;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyContext;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyResult;

public final class PnLibraryPolicy implements RemotePolicy {

    @Override
    public RemotePolicyResult check(RemotePolicyContext context) {
        return RemotePolicyResult.denying("Эта версия больше не поддерживается")
                .status("устарела")                      // вместо «не поддерживается»
                .headline("нужно обновление")            // строка под маской
                .detail("Минимальная версия", "0.0.2")   // строки в блоке с версией
                .reason("Исправлена критическая ошибка") // причины
                .step("Скачайте новую версию")           // раздел «Что делать»
                .step("Перезапустите сервер")
                .link("Скачать", "https://github.com/pnFolder/pnLibrary/releases/latest") // раздел «Ссылки»
                .build();
    }
}
