package com.stooit;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.resolver.dns.DefaultDnsCache;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;

public class Application {

  private static final int PORT = 53;

  public static void main(final String[] args) {
    System.setProperty("java.net.preferIPv4Stack", "true");

    final EventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    final List<DnsNameResolver> resolvers = new LinkedList<>();

    boolean verbose = false;
    int port = PORT;

    var it = Arrays.asList(args).iterator();

    while (it.hasNext()) {
      final String arg = it.next();

      if ("-v".equals(arg)) {
        verbose = true;
      } else if ("-p".equals(arg)) {
        if (!it.hasNext()) {
          throw new IllegalArgumentException("Missing port");
        }

        port = Integer.parseInt(it.next());
      } else {
        resolvers.add(
            new DnsNameResolverBuilder(eventLoopGroup.next())
                .channelType(NioDatagramChannel.class)
                .resolveCache(new DefaultDnsCache())
                .nameServerProvider(
                    new SequentialDnsServerAddressStreamProvider(
                        Arrays.stream(arg.split(","))
                            .map(ip -> new InetSocketAddress(ip, PORT))
                            .toArray(InetSocketAddress[]::new)))
                .build());
      }
    }

    if (resolvers.isEmpty()) {
      resolvers.add(
          new DnsNameResolverBuilder(eventLoopGroup.next())
              .channelType(NioDatagramChannel.class)
              .resolveCache(new DefaultDnsCache())
              .nameServerProvider(
                  new SequentialDnsServerAddressStreamProvider(
                      new InetSocketAddress("1.1.1.1", PORT),
                      new InetSocketAddress("8.8.8.8", PORT)))
              .build());
    }

    try {
      final boolean verboseFinal = verbose;

      final Bootstrap bootstrap = new Bootstrap();
      bootstrap
          .group(eventLoopGroup)
          .channel(NioDatagramChannel.class)
          .handler(
              new ChannelInitializer<NioDatagramChannel>() {
                @Override
                protected void initChannel(final NioDatagramChannel nioDatagramChannel) {
                  nioDatagramChannel.pipeline().addLast(new DatagramDnsQueryDecoder());
                  nioDatagramChannel.pipeline().addLast(new DatagramDnsResponseEncoder());
                  nioDatagramChannel.pipeline().addLast(new DnsProxy(resolvers, verboseFinal));
                }
              })
          .option(ChannelOption.SO_BROADCAST, true);

      final ChannelFuture future = bootstrap.bind(new InetSocketAddress("0.0.0.0", port));
      future.sync().channel().closeFuture().sync();
    } catch (final Exception ex) {
      ex.printStackTrace();
    } finally {
      eventLoopGroup.shutdownGracefully();
    }
  }
}
