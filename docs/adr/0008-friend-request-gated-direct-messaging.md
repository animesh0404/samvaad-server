# ADR 0008: Friend-request-gated direct messaging

## Status

Accepted.

## Context

Samvaad is a private, relationship-based messaging system. Authentication alone
must not grant every user permission to start a direct conversation with every
other user. Before messaging is introduced, V1 therefore needs a minimal social
relationship flow that establishes consent to communicate.

## Decision

### User discovery

An authenticated user may search for another user by **exact username**.
Username is the V1 discovery identifier. Email is not used for messaging
user-discovery in V1.

Discovery responses must use a restricted DTO appropriate for user lookup and
must not expose credentials, session information, or other private account data.

### Friend request lifecycle

V1 supports the following minimal lifecycle:

```text
no relationship
      ↓
friend request sent
      ↓
pending
      ↓
accepted
      ↓
friendship established
```

A recipient may accept or reject an incoming friend request. A sender may cancel
a pending request. A request cannot create a friendship until it is accepted.

V1 does not include blocking, muting, archiving, or other relationship controls
in this lifecycle. Those features remain future extensions.

### Messaging authorization

A direct conversation may be created and messages may be exchanged only after
there is an accepted friendship between the two users.

Authentication proves identity. Friendship proves that the two users are
currently authorized to communicate directly.

The authorization check must therefore occur on the server before conversation
creation and before message persistence.

### Friendship uniqueness

For a pair of users, V1 should maintain at most one active friendship. The
relationship model must be designed so that a future block/unfriend lifecycle can
be added without redefining user identity or message identity.

### Unfriend

Unfriend is intentionally **not required for the initial friend-request slice**.
The relationship model should allow it to be added later without changing the
basic user identity model or the meaning of accepted friendship.

## Consequences

Chat is no longer the first domain feature after authentication. The intended
functional progression is:

```text
authenticate
    ↓
discover user by username
    ↓
send friend request
    ↓
accept request
    ↓
friendship established
    ↓
create/use direct conversation
    ↓
exchange messages
```

The server must keep relationship authorization separate from authentication so
that future relationship controls such as blocking can be introduced without
coupling them to credentials or user identity.

## Source material

- `docs/adr/0002-user-profile-boundary-and-email-ownership.md`
- `docs/adr/0007-user-provisioning-and-authorization.md`
- `docs/Samvaad Product & Design Decisions.md`
- `docs/Samvaad Technical Design.md`
