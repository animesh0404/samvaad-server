# ADR 0013: TLS termination at the deployment edge

## Status

Accepted; implementation pending.

## Context

The Samvaad application currently runs as a Spring Boot service with embedded Tomcat and has been exercised locally over HTTP. The web admin panel will eventually be exposed from the same application artifact.

For VPS and public-internet deployments, TLS certificates and public HTTPS concerns should not be tightly coupled to the application JAR when a deployment edge can provide that responsibility.

## Decision

Public HTTPS will be terminated at the deployment edge in front of the Samvaad application rather than making certificate management a mandatory part of the Samvaad JAR deployment.

The intended production path is:

```text
Internet
   |
 HTTPS :443
   v
TLS-capable reverse proxy / tunnel / edge
   |
 HTTP on a private/local application port
   v
Samvaad executable JAR (embedded Tomcat)
   |
   +--> PostgreSQL
```

A VPS deployment may use a TLS-capable reverse proxy such as Nginx, Caddy, or an equivalent managed edge. A tunnel service may be used for a production-like public testing lab; the specific provider is not part of this architectural decision.

The Samvaad application must therefore remain capable of operating behind a reverse proxy/edge, including correct handling of the web admin panel, REST API, and WebSocket traffic.

Direct application-managed TLS remains possible as a deployment-specific option, but it is not the required/default production architecture.

## Consequences

- Certificate issuance, renewal, and public port 443 handling can be managed outside the application lifecycle.
- The same JAR can run unchanged behind different VPS providers, reverse proxies, or tunnel services.
- Local development can continue to use HTTP without requiring development certificates.
- WebSocket proxying must be included in deployment configuration because Samvaad uses STOMP over WebSocket at `/ws`.
- The application deployment contract must not assume that the externally visible scheme/host/port is identical to the internal application listener.

## Explicitly deferred

- Choice of reverse proxy or managed edge provider.
- Certificate authority/provider and automated certificate renewal mechanism.
- Production domain/DNS configuration.
- Exact proxy headers and Spring forwarded-header configuration.
- VPS hardening/firewall policy.
