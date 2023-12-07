package com.stooit;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import io.netty.channel.AddressedEnvelope;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.resolver.dns.DnsNameResolver;

public class Resolver {

  private final String name;
  private final DnsNameResolver resolver;
  private final boolean lowPriority;
  private boolean responding;

  public Resolver(final String name, final DnsNameResolver resolver, final boolean lowPriority) {
    this.name = name;
    this.resolver = resolver;
    this.lowPriority = lowPriority;
  }

  @SuppressWarnings("unchecked")
  public CompletableFuture<DnsResponse> query(final DnsQuestion question) {
    final CompletableFuture<DnsResponse> future = new CompletableFuture<>();
    resolver
        .query(question)
        .addListener(
            f -> {
              final AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                  (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();

              if (envelope == null) {
                future.completeExceptionally(new RuntimeException());
                return;
              }

              future.complete(envelope.content());
            });

    return future;
  }

  public boolean isResponding() {
    return responding;
  }

  public boolean isLowPriority() {
    return lowPriority;
  }

  public void setResponding(final boolean responding) {
    System.err.println(name + " responding = " + responding);
    this.responding = responding;
  }
}
