package us.myles.ViaVersion.sponge;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import us.myles.ViaVersion.api.Pair;
import us.myles.ViaVersion.api.Via;
import us.myles.ViaVersion.api.platform.ViaInjector;
import us.myles.ViaVersion.sponge.handlers.ViaVersionInitializer;
import us.myles.ViaVersion.sponge.util.ReflectionUtil;
import us.myles.ViaVersion.util.ListWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class SpongeViaInjector implements ViaInjector {
    private List<ChannelFuture> injectedFutures = new ArrayList<>();
    private List<Pair<Field, Object>> injectedLists = new ArrayList<>();

    @Override
    public void inject() throws Exception {
        try {
            Object connection = getServerConnection();
            if (connection == null) {
                throw new Exception("We failed to find the core component 'ServerConnection', please file an issue on our GitHub.");
            }
            for (Field field : connection.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                final Object value = field.get(connection);
                if (value instanceof List) {
                    // Inject the list
                    List wrapper = new ListWrapper((List) value) {
                        @Override
                        public synchronized void handleAdd(Object o) {
                            synchronized (this) {
                                if (o instanceof ChannelFuture) {
                                    try {
                                        injectChannelFuture((ChannelFuture) o);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    };
                    injectedLists.add(new Pair<>(field, connection));
                    field.set(connection, wrapper);
                    // Iterate through current list
                    synchronized (wrapper) {
                        for (Object o : (List) value) {
                            if (o instanceof ChannelFuture) {
                                injectChannelFuture((ChannelFuture) o);
                            } else {
                                break; // not the right list.
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            Via.getPlatform().getLogger().severe("Unable to inject ViaVersion, please post these details on our GitHub and ensure you're using a compatible server version.");
            throw e;
        }
    }

    private void injectChannelFuture(ChannelFuture future) throws Exception {
        try {
            ChannelHandler bootstrapAcceptor = future.channel().pipeline().first();
            try {
                ChannelInitializer<SocketChannel> oldInit = ReflectionUtil.get(bootstrapAcceptor, "childHandler", ChannelInitializer.class);
                ChannelInitializer newInit = new ViaVersionInitializer(oldInit);

                ReflectionUtil.set(bootstrapAcceptor, "childHandler", newInit);
                injectedFutures.add(future);
            } catch (NoSuchFieldException e) {
                throw new Exception("Unable to find core component 'childHandler', please check your plugins. issue: " + bootstrapAcceptor.getClass().getName());

            }
        } catch (Exception e) {
            Via.getPlatform().getLogger().severe("We failed to inject ViaVersion, have you got late-bind enabled with something else?");
            throw e;
        }
    }

    @Override
    public void uninject() {
        // TODO: Uninject from players currently online
        for (ChannelFuture future : injectedFutures) {
            ChannelHandler bootstrapAcceptor = future.channel().pipeline().first();
            try {
                ChannelInitializer<SocketChannel> oldInit = ReflectionUtil.get(bootstrapAcceptor, "childHandler", ChannelInitializer.class);
                if (oldInit instanceof ViaVersionInitializer) {
                    ReflectionUtil.set(bootstrapAcceptor, "childHandler", ((ViaVersionInitializer) oldInit).getOriginal());
                }
            } catch (Exception e) {
                System.out.println("Failed to remove injection handler, reload won't work with connections, please reboot!");
            }
        }
        injectedFutures.clear();

        for (Pair<Field, Object> pair : injectedLists) {
            try {
                Object o = pair.getKey().get(pair.getValue());
                if (o instanceof ListWrapper) {
                    pair.getKey().set(pair.getValue(), ((ListWrapper) o).getOriginalList());
                }
            } catch (IllegalAccessException e) {
                System.out.println("Failed to remove injection, reload won't work with connections, please reboot!");
            }
        }

        injectedLists.clear();
    }

    public static Object getServer() throws Exception {
        Class<?> serverClazz = Class.forName("net.minecraft.server.MinecraftServer");
        for (Method m : serverClazz.getDeclaredMethods()) {
            if (m.getParameterCount() == 0) {
                if ((m.getModifiers() & Modifier.STATIC) == Modifier.STATIC) {
                    if (m.getReturnType().equals(serverClazz)) {
                        return m.invoke(null);
                    }
                }
            }
        }
        throw new Exception("Could not find MinecraftServer static field!");
    }

    @Override
    public int getServerProtocolVersion() throws Exception {
        try {
            Class<?> serverClazz = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = getServer();
            Class<?> pingClazz = Class.forName("net.minecraft.network.ServerStatusResponse");
            Object ping = null;
            // Search for ping method
            for (Field f : serverClazz.getDeclaredFields()) {
                if (f.getType() != null) {
                    if (f.getType().getSimpleName().equals("ServerStatusResponse")) {
                        f.setAccessible(true);
                        ping = f.get(server);
                    }
                }
            }
            if (ping != null) {
                Object serverData = null;
                for (Field f : pingClazz.getDeclaredFields()) {
                    if (f.getType() != null) {
                        if (f.getType().getSimpleName().endsWith("MinecraftProtocolVersionIdentifier")) {
                            f.setAccessible(true);
                            serverData = f.get(ping);
                        }
                    }
                }
                if (serverData != null) {
                    int protocolVersion = -1;
                    for (Field f : serverData.getClass().getDeclaredFields()) {
                        if (f.getType() != null) {
                            if (f.getType() == int.class) {
                                f.setAccessible(true);
                                protocolVersion = (int) f.get(serverData);
                            }
                        }
                    }
                    if (protocolVersion != -1) {
                        return protocolVersion;
                    }
                }
            }
        } catch (Exception e) {
            throw new Exception("Failed to get server", e);
        }
        throw new Exception("Failed to get server");
    }

    public static Object getServerConnection() throws Exception {
        Class<?> serverClazz = Class.forName("net.minecraft.server.MinecraftServer");
        Object server = getServer();
        Object connection = null;
        for (Method m : serverClazz.getDeclaredMethods()) {
            if (m.getReturnType() != null) {
                if (m.getReturnType().getSimpleName().equals("NetworkSystem")) {
                    if (m.getParameterTypes().length == 0) {
                        connection = m.invoke(server);
                    }
                }
            }
        }
        return connection;
    }

}