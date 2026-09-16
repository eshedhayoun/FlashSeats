import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

public class Probe {
  public static void main(String[] args) {
    RedisClient client = RedisClient.create("redis://localhost:6379");
    ProxyManager<byte[]> pm = Bucket4jLettuce.casBasedBuilder(client).build();
    Supplier<BucketConfiguration> conf = () -> BucketConfiguration.builder()
      .addLimit(Bandwidth.builder().capacity(20).refillGreedy(10, Duration.ofSeconds(1)).build())
      .build();
    byte[] key = "probe-key".getBytes(StandardCharsets.UTF_8);
    BucketProxy bucket = pm.builder().build(key, conf);
    for (int i=1;i<=30;i++) {
      boolean ok = bucket.tryConsume(1);
      System.out.println(i + ": " + ok);
      if (i % 5 == 0) { try { Thread.sleep(200); } catch (Exception e) {} }
    }
    client.shutdown();
  }
}
