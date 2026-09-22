# ADR 0013: TLS termination at the deployment edge

## Status

Accepted; public-edge architecture retained; direct application-managed TLS is implemented separately by ADR 0017.

## Context

The Samvaad application runs as a Spring Boot service with embedded Tomcat and now supports application-managed HTTPS for direct-access deployments. For public/VPS deployments, the architectural boundary remains a TLS-capable deployment edge in front of the application.

For VPS and public-internet deployments, TLS certificates and public HTTPS concerns should not be tightly coupled to the application JAR when a deployment edge can provide that responsibility.

## Decision

Public HTTPS will be terminated at the deployment edge in front of the Samvaad application rather than making public certificate management a mandatory part of the Samvaad JAR deployment. This ADR defines the public deployment boundary; ADR 0017 separately defines the implemented application-managed TLS mode for direct access.

The intended production path is:

```text
Internet
   |
 HTTPS :443
   v
TLS-capable reverse proxy / tunnel / edge
   |
 HTTP or HTTPS on a private/local application port
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
- Direct-access development/deployment can use the application's self-signed HTTPS identity without requiring a reverse proxy.
- WebSocket proxying must be included in deployment configuration because Samvaad uses STOMP over WebSocket at `/ws`.
- The application deployment contract must not assume that the externally visible scheme/host/port is identical to the internal application listener.

## Relationship to ADR 0017

ADR 0017 adds a second, complementary deployment mode: Samvaad can terminate HTTPS itself on port `8080` for direct host/LAN access. It does not invalidate this ADR's public-edge boundary. Public deployments may therefore use an edge in front of Samvaad, while direct deployments may connect to the application's HTTPS listener directly.

## Explicitly deferred

- Choice of reverse proxy or managed edge provider.
- Certificate authority/provider and automated certificate renewal mechanism.
- Production domain/DNS configuration.
- Exact proxy headers and Spring forwarded-header configuration.
- VPS hardening/firewall policy.
