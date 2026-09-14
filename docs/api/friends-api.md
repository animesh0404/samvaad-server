# Friends API Contract

## `GET /api/friends`

Returns the authenticated user's current friends.

### Authentication

Authentication is required. The caller is derived from the authenticated server principal; the request does not accept a `userId` query parameter or path parameter.

### Request

```http
GET /api/friends
Authorization: Bearer <access-token>
```

No pagination parameters are defined for this endpoint.

### Response

`200 OK`

Returns a JSON array of safe public user representations. Each friend contains only:

```json
{
  "userId": "<uuid>",
  "username": "<username>"
}
```

No email address, password/password hash, role, or internal user fields are exposed.

When the authenticated user has no friends, the endpoint returns `200 OK` with an empty array:

```json
[]
```

### Friendship semantics

A user is considered a friend when there is an `ACCEPTED` `FriendRequest` involving the authenticated user. Friendship is symmetric regardless of which user originally sent the request.

The endpoint includes accepted requests where the caller is either the sender or recipient. `PENDING`, `REJECTED`, and `CANCELLED` requests are excluded. The caller is never returned as their own friend.

There is no separate `Friendship` entity or table; the accepted friend-request row remains the source of truth for friendship.

### Ordering

Results are sorted by `username` in ascending order before being returned.

### Authorization boundary

The endpoint always operates on the authenticated caller. A caller cannot request another user's friend list by supplying a different user ID.

### Errors

Unauthenticated requests are rejected with `401 Unauthorized` according to the server's established authentication/error handling behavior.
