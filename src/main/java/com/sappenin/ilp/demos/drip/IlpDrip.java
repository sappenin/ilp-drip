package com.sappenin.ilp.demos.drip;

import static okhttp3.CookieJar.NO_COOKIES;

import org.interledger.codecs.ilp.InterledgerCodecContextFactory;
import org.interledger.core.InterledgerAddress;
import org.interledger.core.SharedSecret;
import org.interledger.link.Link;
import org.interledger.link.LinkId;
import org.interledger.link.http.IlpOverHttpLink;
import org.interledger.link.http.auth.SimpleBearerTokenSupplier;
import org.interledger.quilt.jackson.InterledgerModule;
import org.interledger.quilt.jackson.conditions.Encoding;
import org.interledger.spsp.PaymentPointer;
import org.interledger.spsp.StreamConnectionDetails;
import org.interledger.spsp.client.SimpleSpspClient;
import org.interledger.spsp.client.SpspClient;
import org.interledger.stream.Denominations;
import org.interledger.stream.SendMoneyRequest;
import org.interledger.stream.SendMoneyResult;
import org.interledger.stream.sender.FixedSenderAmountPaymentTracker;
import org.interledger.stream.sender.SimpleStreamSender;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.primitives.UnsignedLong;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zalando.problem.ProblemModule;
import org.zalando.problem.violations.ConstraintViolationProblemModule;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example how to use Quilt to send a STREAM payment. See this module's README for more details.
 */
public class IlpDrip {

  private static final Logger LOGGER = LoggerFactory.getLogger("IlpDrip");

  private static final InterledgerAddress OPERATOR_ADDRESS
    = InterledgerAddress.of("private.com.sappenin.ilp.drip.application~send-only");

  // Create SimpleStreamSender for sending STREAM payments
  private static SimpleStreamSender SIMPLE_STREAM_SENDER;

  public static void main(String[] args) throws InterruptedException {

    try {
      final String senderAccountUsername = Optional.ofNullable(args[0])
        .map(val -> {
          LOGGER.debug("Arg0 = `" + val + "`");
          return val;
        })
        .map(String::trim)
        .orElseThrow(() -> new IllegalStateException("Arg0 must contain a ILP source account id."));

      final HttpUrl testNetUrl
        = HttpUrl.parse("https://prod.wc.wallet.xpring.io/accounts/" + senderAccountUsername + "/ilp");

      final String senderAuthToken = Optional.ofNullable(args[1])
        .map(val -> {
          LOGGER.debug("Arg1 = `" + val + "`");
          return val;
        })
        .map(String::trim)
        .orElseThrow(() -> new IllegalStateException("Arg1 must contain a valid ILP auth token."));

      final PaymentPointer receiver1 = Optional.ofNullable(args[2])
        .map(val -> {
          LOGGER.debug("Arg2 = `" + val + "`");
          return val;
        })
        .map(String::trim)
        .map(PaymentPointer::of)
        .orElseThrow(() -> new IllegalStateException("Arg2 must contain a valid Payment Pointer."));

      final Optional<PaymentPointer> receiver2 = Optional.ofNullable(args[3])
        .map(String::trim)
        .map(PaymentPointer::of);

      final SpspClient spspClient = new SimpleSpspClient();

      // Use ILP over HTTP for our underlying link
      final Link link = newIlpOverHttpLink(testNetUrl, senderAuthToken);
      link.setLinkId(LinkId.of("Xpring Sappenin Account"));

      // Create SimpleStreamSender for sending STREAM payments
      SIMPLE_STREAM_SENDER = new SimpleStreamSender(link);

      while (true) {
        LOGGER.info("PAYMENT LINK\n{}\n\n", link);

        CompletableFuture<SendMoneyResult> firstPayment = sendMoney(spspClient, receiver1);
        CompletableFuture<SendMoneyResult> secondPayment = receiver2
          .map(destPaymentPointer -> sendMoney(spspClient, destPaymentPointer))
          .orElseGet(() -> CompletableFuture.completedFuture(null));

        // Wait for both to complete...
        CompletableFuture.allOf(firstPayment, secondPayment).get();
        LOGGER.info("\n"
          + "$$$$$$$$$$$$$$$$$$$$$\n"
          + "ALL PAYMENTS COMPLETE\n"
          + "$$$$$$$$$$$$$$$$$$$$$\n"
        );
      }
    } catch (Exception e) {
      displayUsage();
    }
  }

  /**
   * Show the usage message in the console to help the user use the app.
   */
  private static void displayUsage() {
    LOGGER.info(
      "USAGE: java -jar ilp-drip-1.0-SNAPSHOT-spring-boot.jar {senderAccountUsername} {senderAccountToken} {receiver1PaymentPointer} "
        + "{receiver2PaymentPointer [optional]}");
  }

  private static CompletableFuture<SendMoneyResult> sendMoney(
    final SpspClient spspClient, final PaymentPointer receiverPaymentPointer
  ) {

    // Fetch shared secret and destination address using SPSP client
    final StreamConnectionDetails connectionDetails = spspClient.getStreamConnectionDetails(receiverPaymentPointer);

    // This is 1 drop when scale=9
    //final long ONE_DROP_IN_SCALE_9 = 1000;

    // This is 1 drop when scale=9
    final long ONE_THOUSAND_DROPS_IN_SCALE_9 = 1000_000;

    // Send payment using STREAM
    final SendMoneyRequest sendMoneyRequest = SendMoneyRequest.builder()
      .amount(UnsignedLong.valueOf(ONE_THOUSAND_DROPS_IN_SCALE_9))
      .denomination(Denominations.XRP_MILLI_DROPS)
      .destinationAddress(connectionDetails.destinationAddress())
      .timeout(Duration.ofMillis(60000))
      .paymentTracker(new FixedSenderAmountPaymentTracker(UnsignedLong.valueOf(ONE_THOUSAND_DROPS_IN_SCALE_9)))
      .sharedSecret(SharedSecret.of(connectionDetails.sharedSecret().value()))
      .build();
    LOGGER
      .info("SEND MONEY REQUEST\n{}\n\n",
        sendMoneyRequest);

    return SIMPLE_STREAM_SENDER.sendMoney(sendMoneyRequest).whenComplete((sendMoneyResult, throwable) -> {
      LOGGER.info("SEND MONEY SUCCESS\n{}\n\n", sendMoneyResult);
    });
  }

  private static Link newIlpOverHttpLink(final HttpUrl testNetUrl, final String senderAuthToken) {
    Objects.requireNonNull(testNetUrl);
    Objects.requireNonNull(senderAuthToken);

    return new IlpOverHttpLink(
      () -> OPERATOR_ADDRESS,
      testNetUrl,
      newHttpClient(),
      createObjectMapperForProblemsJson(),
      InterledgerCodecContextFactory.oer(),
      new SimpleBearerTokenSupplier(senderAuthToken)
    );
  }

  private static OkHttpClient newHttpClient() {
    ConnectionPool connectionPool = new ConnectionPool(10, 5, TimeUnit.MINUTES);
    ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).build();
    OkHttpClient.Builder builder = new OkHttpClient.Builder()
      .connectionSpecs(Arrays.asList(spec, ConnectionSpec.CLEARTEXT))
      .cookieJar(NO_COOKIES)
      .connectTimeout(5000, TimeUnit.MILLISECONDS)
      .readTimeout(35, TimeUnit.SECONDS)
      .writeTimeout(35, TimeUnit.SECONDS);
    return builder.connectionPool(connectionPool).build();
  }

  /**
   * Construct an {@link ObjectMapper} that can be used to serialize and deserialize ProblemsJSON where JSON numbers
   * emit as non-String values. Because Problems+Json requires HTTP status codes to be serialized as numbers (and not
   * Strings) per RFC-7807, this ObjectMapper should not be used for payloads that involve Problems.
   *
   * @return An {@link ObjectMapper}.
   *
   * @see "https://tools.ietf.org/html/rfc7807"
   */
  private static ObjectMapper createObjectMapperForProblemsJson() {
    return new ObjectMapper()
      .registerModule(new Jdk8Module())
      .registerModule(new InterledgerModule(Encoding.BASE64))
      .registerModule(new ProblemModule())
      .registerModule(new ConstraintViolationProblemModule())
      .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
      .configure(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, false);
  }

}
