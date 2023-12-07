Simple Java DNS Proxy that forwards a query to multiple upstream servers and return the first non-NXDOMAIN response.

This is a very quick & dirty dev to work around split-horizon DNS issue when on multiple VPNs with multiple DNS servers answering for the same domain but with different scopes

Note: base code taken from https://github.com/nzhenry/dns-proxy/tree/master

Note2: I'd not recommand using this as the main and only DNS server. I personnally setup a dnsmasq on my machine with a default upstream server to 1.1.1.1, and only set 127.0.0.1#153 (my dns proxy port) to answer for my split horizon domains
