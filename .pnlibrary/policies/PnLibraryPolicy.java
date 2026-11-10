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
 * красной рамкой в консоли (в начале запуска, посередине и после запуска сервера) и красным сообщением администраторам
 * через несколько секунд после входа на Bukkit-сервер.
 *
 * Код политики исполняется на сервере, как код плагина. Bukkit используется только через рефлексию, чтобы политика
 * компилировалась на любом сервере. Всё, что выполняется после проверки, лежит в отдельных классах, которые
 * загружаются заранее: загрузчик политики закрывается сразу после проверки.
 */
public final class PnLibraryPolicy implements RemotePolicy {

    /** Новый сервер поддержки. */
    private static final String DISCORD = "https://discord.gg/jqRFJC6WDM";

    /** Рамка для консоли. Один элемент массива: одна строка лога. */
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

    /** Когда печатать рамку, в тиках от проверки (20 тиков = 1 секунда): сразу, посередине запуска и после него. */
    private static final long[] CONSOLE_DELAYS_TICKS = {0L, 140L, 300L};

    /** Сообщение администратору при входе на сервер (цвета через §, красное). */
    private static final String ADMIN_MESSAGE =
        "§c§l[pnLibrary] §cМы переехали на новый Discord-сервер: §e" + DISCORD
            + " §c(старый больше не поддерживается)";

    /** Через сколько тиков после входа показать сообщение администратору. */
    private static final long ADMIN_DELAY_TICKS = 60L;

    /** Право, по которому игрок считается администратором. Операторы видят сообщение всегда. */
    private static final String ADMIN_PERMISSION = "pnlibrary.admin";

    /** Через сколько часов повторять всю серию рамок в консоли. 0 — только один раз при запуске сервера. */
    private static final int CONSOLE_REPEAT_HOURS = 0;

    private static final String SHOWN_PROPERTY = "pnlibrary.policy.moveNotice.shownAt";
    private static final String HOOK_PROPERTY = "pnlibrary.policy.moveNotice.hooked";

    @Override
    public RemotePolicyResult check(RemotePolicyContext context) {
        try {
            Plan plan = new Plan(context);
            scheduleConsoleNotice(plan);
            if (context.getPlatform().getType() == PlatformType.BUKKIT) hookAdminJoin(plan);
        } catch (Throwable ignored) {
            // Объявление не должно мешать проверке: плагин работает в любом случае.
        }
        return RemotePolicyResult.allow();
    }

    private static void scheduleConsoleNotice(Plan plan) {
        long now = System.currentTimeMillis();
        String shown = System.getProperty(SHOWN_PROPERTY);
        if (shown != null) {
            long interval = CONSOLE_REPEAT_HOURS * 3_600_000L;
            if (interval <= 0 || now - Long.parseLong(shown) < interval) return;
        }
        System.setProperty(SHOWN_PROPERTY, Long.toString(now));
        new Notice(plan).toString();
        new Later(null, 0L).toString();
        for (long delay : CONSOLE_DELAYS_TICKS) {
            Notice notice = new Notice(plan);
            if (delay <= 0L) notice.run();
            else runLater(plan, notice, delay);
        }
    }

    /**
     * Запускает задачу позже: планировщиком Bukkit, а если его нет (Velocity, BungeeCord, Folia), потоком с паузой.
     */
    private static void runLater(Plan plan, Runnable task, long ticks) {
        if (plan.bukkitClass != null) {
            try {
                Object scheduler = plan.bukkitClass.getMethod("getScheduler").invoke(null);
                Method run = scheduler.getClass().getMethod("runTaskLater", plan.pluginType, Runnable.class, long.class);
                run.invoke(scheduler, plan.plugin, task, ticks);
                return;
            } catch (Throwable unavailable) {
                // Нет планировщика Bukkit (Folia): ниже запасной вариант.
            }
        }
        Thread thread = new Thread(new Later(task, ticks * 50L), "pnlibrary-notice");
        thread.setDaemon(true);
        thread.start();
    }

    /** Один раз за запуск сервера подписывается на вход игрока; повторные проверки ничего не добавляют. */
    private static void hookAdminJoin(Plan plan) throws Exception {
        if (System.getProperty(HOOK_PROPERTY) != null || plan.plugin == null || plan.pluginType == null) return;
        ClassLoader loader = plan.loader;
        Class<?> listener = Class.forName("org.bukkit.event.Listener", true, loader);
        Class<?> executor = Class.forName("org.bukkit.plugin.EventExecutor", true, loader);
        Class<?> event = Class.forName("org.bukkit.event.player.PlayerJoinEvent", true, loader);
        Class<?> priority = Class.forName("org.bukkit.event.EventPriority", true, loader);
        Class<?> pluginManagerType = Class.forName("org.bukkit.plugin.PluginManager", true, loader);
        Object manager = plan.context.nativeHandle(pluginManagerType);
        if (manager == null) return;

        JoinHandler handler = new JoinHandler(plan);
        handler.warmUp();
        Object listenerProxy = Proxy.newProxyInstance(loader, new Class<?>[] {listener}, handler);
        Object executorProxy = Proxy.newProxyInstance(loader, new Class<?>[] {executor}, handler);
        Method register = pluginManagerType.getMethod("registerEvent",
            Class.class, listener, priority, executor, plan.pluginType);
        register.invoke(manager, event, listenerProxy, enumConstant(priority, "NORMAL"), executorProxy, plan.plugin);
        System.setProperty(HOOK_PROPERTY, "true");
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (name.equals(constant.toString())) return constant;
        }
        throw new IllegalStateException(name);
    }

    /** Всё, что нужно после проверки: плагин, классы Bukkit и контекст. */
    private static final class Plan {
        final RemotePolicyContext context;
        final Object plugin;
        final ClassLoader loader;
        Class<?> pluginType;
        Class<?> bukkitClass;

        Plan(RemotePolicyContext context) {
            this.context = context;
            boolean bukkit = context.getPlatform().getType() == PlatformType.BUKKIT;
            this.plugin = bukkit ? context.nativeHandle(Object.class) : null;
            this.loader = plugin == null ? PnLibraryPolicy.class.getClassLoader() : plugin.getClass().getClassLoader();
            if (plugin != null) {
                try {
                    pluginType = Class.forName("org.bukkit.plugin.Plugin", true, loader);
                    bukkitClass = Class.forName("org.bukkit.Bukkit", true, loader);
                } catch (Throwable unavailable) {
                    pluginType = null;
                    bukkitClass = null;
                }
            }
        }
    }

    /** Одна печать рамки. На Bukkit идёт через консоль, где цвета показываются красным; иначе обычным логом. */
    private static final class Notice implements Runnable {
        private final Plan plan;

        Notice(Plan plan) {
            this.plan = plan;
        }

        @Override
        public void run() {
            try {
                Object console = plan.bukkitClass == null ? null : plan.bukkitClass.getMethod("getConsoleSender").invoke(null);
                Method send = console == null ? null : console.getClass().getMethod("sendMessage", String.class);
                Logger logger = Logger.getLogger("pnLibrary");
                for (String line : CONSOLE) {
                    if (send != null) send.invoke(console, "§c" + line);
                    else logger.info(line);
                }
            } catch (Throwable ignored) {
                // Не вышло напечатать: плагин работает дальше.
            }
        }
    }

    /** Запасной вариант отложенного запуска без планировщика Bukkit. */
    private static final class Later implements Runnable {
        private final Runnable task;
        private final long millis;

        Later(Runnable task, long millis) {
            this.task = task;
            this.millis = millis;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(millis);
                if (task != null) task.run();
            } catch (Throwable ignored) {
                // Поток остановлен вместе с сервером.
            }
        }
    }

    /** Слушатель входа игрока: показывает сообщение администратору через ADMIN_DELAY_TICKS. */
    private static final class JoinHandler implements InvocationHandler {
        private final Plan plan;
        private Method getPlayer;
        private Method isOp;
        private Method hasPermission;
        private Method sendMessage;
        private Method isOnline;

        JoinHandler(Plan plan) {
            this.plan = plan;
        }

        void warmUp() {
            String.valueOf(ADMIN_MESSAGE).length();
            String.valueOf(ADMIN_PERMISSION).length();
            new Delivery(this, null).toString();
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
                if (admin) runLater(plan, new Delivery(this, player), ADMIN_DELAY_TICKS);
            } catch (Throwable ignored) {
                // Сообщение администратору не должно ломать вход игрока.
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

    /** Отложенная отправка сообщения одному администратору. */
    private static final class Delivery implements Runnable {
        private final JoinHandler handler;
        private final Object player;

        Delivery(JoinHandler handler, Object player) {
            this.handler = handler;
            this.player = player;
        }

        @Override
        public void run() {
            if (player != null) handler.deliver(player);
        }
    }
}
