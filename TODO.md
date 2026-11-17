# TODO

Ideas to revisit later. Move an item out of here once it's built.

## Plugins

### gRPC support
**What:** show gRPC calls like REST calls in Network: service and method, status, metadata (headers),
timing, and request/response messages converted to JSON. Streaming calls show each message
separately, like the WebSockets tab.

**Why it isn't covered today:** gRPC-Java on Android runs on its own transport, below Slicker's
OkHttp interceptor, so its calls don't appear in Network. Even if they did, bodies are binary
protobuf.

**How it could work:** a gRPC `ClientInterceptor` (`Slicker.grpcInterceptor()`) added to the
channel, using protobuf's `JsonFormat` to turn messages into JSON.

**When:** only worth it for apps that use gRPC (`io.grpc` dependencies, `.proto` files).
