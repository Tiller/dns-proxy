package com.stooit;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;

public class DnsProxy extends SimpleChannelInboundHandler<DatagramDnsQuery> {

  private static final List<DnsSection> DNS_SECTIONS;

  private final List<DnsNameResolver> resolvers;
  private boolean verbose;

  static {
    DNS_SECTIONS = Arrays.asList(DnsSection.values());
  }

  public DnsProxy(final List<DnsNameResolver> resolvers, final boolean verbose) {
    this.resolvers = resolvers;
    this.verbose = verbose;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected void channelRead0(final ChannelHandlerContext context, final DatagramDnsQuery query) {
    final DnsQuestion srcQuestion = query.recordAt(DnsSection.QUESTION, 0);

    if (verbose) {
      System.err.println("Received query: " + srcQuestion.name() + ", " + srcQuestion.type());
    }
    final AtomicInteger nbRes = new AtomicInteger(0);
    final AtomicInteger res = new AtomicInteger(1);

    for (final DnsNameResolver resolver : resolvers) {
      final DnsQuestion question = new DefaultDnsQuestion(srcQuestion.name(), srcQuestion.type());
      resolver
          .query(question)
          .addListener(
              f -> {
                final AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                    (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();

                if (envelope == null) {
                  nbRes.incrementAndGet();
                  return;
                }

                final DnsResponse response = envelope.content();
                final DnsResponse forwardResponse = getResponse(envelope.content(), query);

                if (!DnsResponseCode.NXDOMAIN.equals(response.code())
                    && res.decrementAndGet() == 0) {

                  if (verbose) {
                    System.err.println(
                        "Answering with " + forwardResponse + "\nDerived from " + response);
                  }

                  context.writeAndFlush(forwardResponse);

                } else if (nbRes.incrementAndGet() == resolvers.size()) {
                  if (verbose) {
                    System.err.println(
                        "Answering by default with "
                            + forwardResponse
                            + "\nDerived from "
                            + response);
                  }
                  context.writeAndFlush(forwardResponse);
                } else {
                  forwardResponse.release();
                }
              });
    }
  }

  private DnsResponse getResponse(final DnsResponse serverResponse, final DatagramDnsQuery query) {
    final DnsResponse response =
        new DatagramDnsResponse(query.recipient(), query.sender(), query.id());
    copySections(serverResponse, response);
    return response;
  }

  private void copySections(final DnsResponse r1, final DnsResponse r2) {
    for (final DnsSection section : DNS_SECTIONS) {
      copySection(r1, r2, section);
    }
  }

  private void copySection(final DnsResponse r1, final DnsResponse r2, final DnsSection section) {
    for (int i = 0; i < r1.count(section); i++) {
      r2.addRecord(section, r1.recordAt(section, i));
    }
  }
}
