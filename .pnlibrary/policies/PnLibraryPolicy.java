package pnfolder.policy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;
import org.pnfolder.pnlibrary.api.platform.PlatformType;
import org.pnfolder.pnlibrary.api.remote.RemotePolicy;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyContext;
import org.pnfolder.pnlibrary.api.remote.RemotePolicyResult;

/**
 * Политика самой pnLibrary. Всегда разрешает работу, а заодно сообщает о переезде поддержки на новый Discord-сервер:
 * рамкой в консоли и сообщением администраторам при входе на Bukkit-сервер.
 *
 * Код политики исполняется на сервере, как код плагина. Bukkit используется только через рефлексию, чтобы политика
 * компилировалась на любом сервере; всё, что нужно слушателю после проверки, загружается заранее.
 */
public final class PnLibraryPolicy implements RemotePolicy {

    /** Новый сервер поддержки. */
    private static final String DISCORD = "https://discord.gg/jqRFJC6WDM";

    /** Рамка для консоли. Одна строка массива: одна строка лога. */
    private static final String[] CONSOLE = {
        "========================================================",
        " pnLibrary: мы переехали на новый Discord-сервер",
        "",
        " Старый сервер больше не поддерживается: у него нет",
        " активного владельца. Поддержка, новости и обновления",
        " теперь здесь: " + DISCORD,
        "",
        " Плагины работают как обычно, ничего менять не нужно.",
        "========================================================",
    };

    /** Сообщение администратору при входе на сервер (цвета через §, красное). */
    private static final String ADMIN_MESSAGE =
        "§c§l[pnLibrary] §cМы переехали на новый Discord-сервер: §e" + DISCORD
            + " §c(старый больше не поддерживается)";

    /** Сколько раз подряд печатать рамку в консоли, чтобы её было трудно не заметить. */
    private static final int CONSOLE_COPIES = 3;

    /** Через сколько тиков после входа показать сообщение администратору (20 тиков = 1 секунда). */
    private static final long ADMIN_DELAY_TICKS = 60L;

    /** Право, по которому игрок считается администратором. Операторы видят сообщение всегда. */
    private static final String ADMIN_PERMISSION = "pnlibrary.admin";

    /** Через сколько часов повторять рамку в консоли. 0 — только один раз при запуске сервера. */
    private static final int CONSOLE_REPEAT_HOURS = 0;

    private static final String SHOWN_PROPERTY = "pnlibrary.policy.moveNotice.shownAt";
    private static final String HOOK_PROPERTY = "pnlibrary.policy.moveNotice.hooked";

    @Override
    public RemotePolicyResult check(RemotePolicyContext context) {
        try {
            printConsoleNotice(context);
            if (context.getPlatform().getType() == PlatformType.BUKKIT) hookAdminJoin(context);
        } catch (Throwable ignored) {
            // Объявление не должно мешать проверке: плагин работает в любом случае.
        }
        return RemotePolicyResult.allow();
    }

    private static void printConsoleNotice(RemotePolicyContext context) {
        long now = System.currentTimeMillis();
        String shown = System.getProperty(SHOWN_PROPERTY);
        if (shown != null) {
            long interval = CONSOLE_REPEAT_HOURS * 3_600_000L;
            if (interval <= 0 || now - Long.parseLong(shown) < interval) return;
        }
        System.setProperty(SHOWN_PROPERTY, Long.toString(now));
        Object console = bukkitConsole(context);
        Logger logger = Logger.getLogger("pnLibrary");
        for (int copy = 0; copy < CONSOLE_COPIES; copy++) {
            for (String line : CONSOLE) {
                if (console != null) sendToConsole(console, "§c" + line);
                else logger.info(line);
            }
        }
    }

    /** Консоль Bukkit, если это Bukkit-сервер: через неё цвета § показываются красным. Иначе null. */
    private static Object bukkitConsole(RemotePolicyContext context) {
        try {
            if (context.getPlatform().getType() != PlatformType.BUKKIT) return null;
            Object plugin = context.nativeHandle(Object.class);
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", true, plugin.getClass().getClassLoader());
            return bukkit.getMethod("getConsoleSender").invoke(null);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static void sendToConsole(Object console, String line) {
        try {
            console.getClass().getMethod("sendMessage", String.class).invoke(console, line);
        } catch (Throwable ignored) {
            // Не вышло через консоль Bukkit: строку не показываем, плагин работает дальше.
        }
    }

    /** Один раз за запуск сервера подписывается на вход игрока; повторные проверки ничего не добавляют. */
    private static void hookAdminJoin(RemotePolicyContext context) throws Exception {
        if (System.getProperty(HOOK_PROPERTY) != null) return;
        Object plugin = context.nativeHandle(Object.class);
        if (plugin == null) return;
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> listener = Class.forName("org.bukkit.event.Listener", true, loader);
        Class<?> executor = Class.forName("org.bukkit.plugin.EventExecutor", true, loader);
        Class<?> event = Class.forName("org.bukkit.event.player.PlayerJoinEvent", true, loader);
        Class<?> priority = Class.forName("org.bukkit.event.EventPriority", true, loader);
        Class<?> pluginType = Class.forName("org.bukkit.plugin.Plugin", true, loader);
        Class<?> pluginManagerType = Class.forName("org.bukkit.plugin.PluginManager", true, loader);
        Object manager = context.nativeHandle(pluginManagerType);
        if (manager == null) return;

        JoinHandler handler = new JoinHandler(plugin, pluginType, loader);
        handler.warmUp();
        Object listenerProxy = Proxy.newProxyInstance(loader, new Class<?>[] {listener}, handler);
        Object executorProxy = Proxy.newProxyInstance(loader, new Class<?>[] {executor}, handler);
        Method register = pluginManagerType.getMethod("registerEvent",
            Class.class, listener, priority, executor, pluginType);
        register.invoke(manager, event, listenerProxy, enumConstant(priority, "NORMAL"), executorProxy, plugin);
        System.setProperty(HOOK_PROPERTY, "true");
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (name.equals(constant.toString())) return constant;
        }
        throw new IllegalStateException(name);
    }

    /**
     * Слушатель входа. Живёт дольше проверки, поэтому не использует лямбды и вложенные классы, которые подгружались бы
     * из уже закрытого загрузчика политики: всё нужное загружает warmUp().
     */
    private static final class JoinHandler implements InvocationHandler {
        private final Object plugin;
        private final Class<?> pluginType;
        private final ClassLoader loader;
        private Method getPlayer;
        private Method isOp;
        private Method hasPermission;
        private Method sendMessage;
        private Method isOnline;
        private Method runTaskLater;
        private Object scheduler;

        JoinHandler(Object plugin, Class<?> pluginType, ClassLoader loader) {
            this.plugin = plugin;
            this.pluginType = pluginType;
            this.loader = loader;
        }

        /** Загружает все классы, которые понадобятся после закрытия загрузчика политики. */
        void warmUp() {
            String.valueOf(ADMIN_MESSAGE).length();
            String.valueOf(ADMIN_PERMISSION).length();
            new Delayed(this, null).toString();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("execute".equals(name) && args != null && args.length == 2) {
                onJoin(args[1]);
                return null;
            }
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == args[0];
            if ("toString".equals(name)) return "pnLibrary move notice";
            return null;
        }

        private void onJoin(Object event) {
            try {
                if (getPlayer == null) {
                    getPlayer = event.getClass().getMethod("getPlayer");
                    Class<?> player = getPlayer.getReturnType();
                    isOp = player.getMethod("isOp");
                    hasPermission = player.getMethod("hasPermission", String.class);
                    sendMessage = player.getMethod("sendMessage", String.class);
                    isOnline = player.getMethod("isOnline");
                }
                Object player = getPlayer.invoke(event);
                boolean admin = Boolean.TRUE.equals(isOp.invoke(player))
                    || Boolean.TRUE.equals(hasPermission.invoke(player, ADMIN_PERMISSION));
                if (!admin) return;
                if (!scheduleLater(player)) deliver(player);
            } catch (Throwable ignored) {
                // Сообщение администратору не должно ломать вход игрока.
            }
        }

        /** Показывает сообщение позже, когда игрок уже в игре. false, если планировщик недоступен (например, Folia). */
        private boolean scheduleLater(Object player) {
            try {
                if (runTaskLater == null) {
                    Class<?> bukkit = Class.forName("org.bukkit.Bukkit", true, loader);
                    scheduler = bukkit.getMethod("getScheduler").invoke(null);
                    runTaskLater = scheduler.getClass().getMethod("runTaskLater",
                        pluginType, Runnable.class, long.class);
                }
                runTaskLater.invoke(scheduler, plugin, new Delayed(this, player), ADMIN_DELAY_TICKS);
                return true;
            } catch (Throwable unavailable) {
                return false;
            }
        }

        void deliver(Object player) {
            try {
                if (Boolean.TRUE.equals(isOnline.invoke(player))) sendMessage.invoke(player, ADMIN_MESSAGE);
            } catch (Throwable ignored) {
                // Игрок мог выйти: сообщать нечего.
            }
        }
    }

    /** Отложенная отправка. Отдельный класс, а не лямбда, чтобы он гарантированно был загружен заранее. */
    private static final class Delayed implements Runnable {
        private final JoinHandler handler;
        private final Object player;

        Delayed(JoinHandler handler, Object player) {
            this.handler = handler;
            this.player = player;
        }

        @Override
        public void run() {
            if (player != null) handler.deliver(player);
        }
    }
}
