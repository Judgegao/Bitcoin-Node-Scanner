package connect;

import com.fasterxml.jackson.databind.JsonNode;
import connect.data.NodeHashMap;
import connect.data.StateBundle;
import constant.Constants;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import message.data.IPv6;
import message.data.NetAddr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.Lookup;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.SQLOutput;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class ConnectionManager {

    private EventLoopGroup worker;
    private final int nThreads = 8;
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);
    public static final NodeHashMap map = new NodeHashMap();
    public static ConcurrentHashMap<String, Boolean> connection = new ConcurrentHashMap<>();
    public static final LinkedBlockingQueue<NetAddr> queue = new LinkedBlockingQueue<>();

    public ConnectionManager() {
        worker = new NioEventLoopGroup(nThreads);
    }

    @SuppressWarnings("unchecked")
    private void readyQueue() throws Exception {
        File file = new File("node.json");
        if (!file.exists()) {
            // if node.json is not exist, lookup seed.
            InetAddress[] address = Lookup.lookup(Constants.SEED_DNS[0]);
            if (address == null) {
                logger.info("Lookup seed fail");
                System.exit(0);
            }

            for (InetAddress addr : address) {
                connection.put(IPv6.convert(addr.getHostAddress()), false);
                queue.put(new NetAddr(0, 0, addr.getHostAddress(), 8333));
            }

            Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, connection);
            return;
        }

        connection = Constants.om.readValue(file, ConcurrentHashMap.class);

        file = new File("queue.json");
        for (JsonNode node : Constants.om.readTree(file)) {
            long time = node.get("time").asLong();
            long services = node.get("services").asLong();
            IPv6 ip = new IPv6((node.get("ip").asText()));
            int port = node.get("port").asInt();
            queue.add(new NetAddr(time, services, ip, port));
        }
    }

    private static int count = 0;

    synchronized public static void done() throws InterruptedException {
        count++;
        if (Constants.WRITE_INTERVAL == 0 || count % Constants.WRITE_INTERVAL == 0) {
            try {
                coverFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void start2(final int goal) throws Exception {
        Bootstrap bs = getNewBootstrap(worker);
        readyQueue();
        logger.info("Queue: " + queue.size());
        if (queue.size() == 0) {
            System.exit(0);
        }
        try {

            final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
            while (count < goal) {
                while (channels.size() <= nThreads) {
                    System.out.println("[Monitor] channel size = " + channels.size() + ", map size = " + map.size());
                    try {
                        NetAddr target = queue.take();
                        if (!connection.getOrDefault(target.getIp(), false)) {
                            StateBundle bundle = new StateBundle(target.getIp(), target.getPort(), System.currentTimeMillis());
                            ConnectionHandler.map.put(target.getIp().toString(), bundle);
                            connection.put(target.getIp().toString(), true);
                            ChannelFuture future = bs.connect(target.getIp().toString(), target.getPort());
                            channels.add(future.channel());
                        }
                    } catch (ClassCastException e1) {
                        logger.warn(":::: Exception", e1);
                        count = goal;
                        break;
                    } catch (Exception e2) {
                        logger.warn(":::: Exception", e2);
                    }
                }
            }

            channels.close().sync();
        } finally {
            worker.shutdownGracefully().sync();
        }

        writeSet();
    }

    private void writeSet() throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        Date date = new Date();
        File file = new File("data" + File.separator + sdf.format(date) + "-node.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, map);
        logger.info("Map<String, NetAddr> Finish");
        file = new File("data" + File.separator + sdf.format(date) + "-bundle.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, ConnectionHandler.map);
        logger.info("Map<String, StateBundle> Finish");
        file = new File("node.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, connection);
        logger.info("Map<String, Boolean> Finish");
        file = new File("queue.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, queue);
        logger.info("Queue<NetAddr> Finish");
    }


    private static void coverFile() throws IOException {

        backup();

        File file = new File("data" + File.separator + "current-node.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, map);
        logger.info("Map<String, NetAddr> Finish");

        file = new File("data" + File.separator + "current-bundle.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, ConnectionHandler.map);
        logger.info("Map<String, StateBundle> Finish");

        file = new File("node.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, connection);
        logger.info("Map<String, Boolean> Finish");

        file = new File("queue.json");
        Constants.om.writerWithDefaultPrettyPrinter().writeValue(file, queue);
        logger.info("Queue<NetAddr> Finish");
    }

    /**
     * Backup last version, avoid program breakdown.
     */
    private static void backup() {
        String[] names = new String[] {"-node.json", "-bundle.json"};
        for (String name : names) {
            File file = new File("data" + File.separator + "current" + name);
            if (file.exists()) {
                File backup = new File("data" + File.separator + "backup" + name);
                if (backup.exists()) {
                    if (backup.delete()) {
                        file.renameTo(backup);
                    }
                } else {
                    file.renameTo(backup);
                }
            }
        }
    }

    private Bootstrap getNewBootstrap(EventLoopGroup group) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        bootstrap.group(group);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel socketChannel) throws Exception {
                socketChannel.pipeline().addLast(new MessageDecoder(), new ConnectionHandler());
            }
        });

        return bootstrap;
    }
}
