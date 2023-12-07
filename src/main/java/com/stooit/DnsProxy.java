package com.stooit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;

public class DnsProxy extends SimpleChannelInboundHandler<DatagramDnsQuery> {

  private static final List<DnsSection> DNS_SECTIONS;

  private final List<Resolver> resolvers;
  private final boolean verbose;

  static {
    DNS_SECTIONS = Arrays.asList(DnsSection.values());
  }

  public DnsProxy(final List<Resolver> resolvers, final boolean verbose) {
    this.resolvers = resolvers;
    this.verbose = verbose;
  }

  @Override
  protected void channelRead0(final ChannelHandlerContext context, final DatagramDnsQuery query) {
    final DnsQuestion srcQuestion = query.recordAt(DnsSection.QUESTION, 0);

    if (verbose) {
      System.err.println("Received query: " + srcQuestion.name() + ", " + srcQuestion.type());
    }
    final DnsQuestion question = new DefaultDnsQuestion(srcQuestion.name(), srcQuestion.type());

    final ResponseProcessor processor = new ResponseProcessor(context, query, verbose);
    for (final Resolver resolver : resolvers) {
      resolver
          .query(question)
          .orTimeout(10, TimeUnit.SECONDS)
          .whenComplete((r, th) -> processor.processResponse(r, th, resolver));
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

  private class ResponseProcessor {

    private final Set<Resolver> received = new HashSet<>();
    private final ChannelHandlerContext context;
    private final DatagramDnsQuery query;
    private boolean sent;
    private boolean verbose;

    private DnsResponse lowPriorityResponse;
    private DnsResponse nxResponse;

    public ResponseProcessor(
        final ChannelHandlerContext context, final DatagramDnsQuery query, final boolean verbose) {
      this.context = context;
      this.query = query;
      this.verbose = verbose;
    }

    public void processResponse(
        final DnsResponse response, final Throwable th, final Resolver resolver) {

      received.add(resolver);

      if (th != null) {
        if (resolver.isResponding()) {
          resolver.setResponding(false);
        }

        if (!sent) {
          final boolean allCompleted =
              resolvers.stream().filter(Resolver::isResponding).allMatch(received::contains);

          if (allCompleted) {
            if (lowPriorityResponse != null) {
              send(lowPriorityResponse);
            } else if (nxResponse != null) {
              send(nxResponse);
            }
          }
        }
        return;
      }

      if (!resolver.isResponding()) {
        resolver.setResponding(true);
      }

      if (sent) {
        return;
      }

      if (DnsResponseCode.NXDOMAIN.equals(response.code())) {
        if (nxResponse == null) {
          nxResponse = response;
        }
      } else if (resolver.isLowPriority()) {
        if (lowPriorityResponse == null) {
          lowPriorityResponse = response;
        }
      } else {
        send(response);
        return;
      }

      final boolean mainCompleted =
          resolvers.stream()
              .filter(Resolver::isResponding)
              .filter(Predicate.not(Resolver::isLowPriority))
              .allMatch(received::contains);

      if (mainCompleted && lowPriorityResponse != null) {
        send(lowPriorityResponse);
        return;
      }

      final boolean lowPriorityCompleted =
          resolvers.stream()
              .filter(Resolver::isResponding)
              .filter(Resolver::isLowPriority)
              .allMatch(received::contains);

      if (mainCompleted && lowPriorityCompleted) {
        send(nxResponse);
      }
    }

    private void send(final DnsResponse response) {
      final DnsResponse forwardResponse = getResponse(response, query);

      if (verbose) {
        System.err.println("Answering with " + forwardResponse + "\nDerived from " + response);
      }
      
      context.writeAndFlush(forwardResponse);
      sent = true;
    }
  }
}
