# turntf-java

`turntf-java` is a Java SDK for turntf. It provides:

- blocking HTTP JSON client APIs
- WebSocket + protobuf realtime client APIs
- automatic reconnect and re-login
- `saveMessage -> saveCursor -> ack` message reliability

Quick start:

```java
import io.github.tursom.turntf.java.*;

var client = new TurntfClient(new Config(
    "http://127.0.0.1:8080",
    new Credentials(4096, 1025, PasswordInput.plain("alice-password"))
));

client.connect().join();
client.sendMessage(new SendMessageInput(new UserRef(4096, 1025), "hello".getBytes())).join();
client.close();
```
