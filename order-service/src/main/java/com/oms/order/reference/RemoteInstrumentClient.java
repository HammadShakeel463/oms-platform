package com.oms.order.reference;

import com.oms.common.error.NotFoundException;
import com.oms.common.error.UpstreamUnavailableException;
import com.oms.common.reference.InstrumentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches instruments from market-data-service over REST, cached in Redis.
 *
 * <p><b>Why {@code RestClient}.</b> Spring has three HTTP clients and picking the right one
 * is a routine interview question. {@code RestTemplate} is in maintenance mode.
 * {@code WebClient} is reactive and excellent, but using it from a blocking servlet thread
 * means {@code .block()}, which is a reactive pipeline pretending to be synchronous - two
 * thread pools and a worse stack trace for no benefit. {@code RestClient} (Spring 6.1+) is
 * the synchronous client built on the same infrastructure as {@code WebClient}: a fluent
 * API, blocking semantics, no reactive types. On a blocking service it is the right default.
 *
 * <p><b>Why caching is more than a speed-up here.</b> Lot size, tick size and the price band
 * change essentially never. Caching them turns a hard dependency into a soft one: if
 * market-data-service is down, orders in already-seen symbols keep validating against
 * data that is minutes old and almost certainly still correct. A validation path that
 * hard-fails when another service restarts is worse than one that validates against a
 * five-minute-old lot size.
 */
@Component
@ConditionalOnProperty(name = "oms.reference.source", havingValue = "remote", matchIfMissing = true)
public class RemoteInstrumentClient implements InstrumentClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteInstrumentClient.class);

    private final RestClient restClient;

    public RemoteInstrumentClient(RestClient marketDataRestClient) {
        this.restClient = marketDataRestClient;
    }

    /**
     * {@code @Cacheable} is a proxy-based aspect: Spring wraps this bean, and a cache hit
     * returns without the method body running at all.
     *
     * <p>The consequence that catches people out: the proxy only intercepts calls that
     * arrive from <em>outside</em> the bean. A call from another method of this same class
     * goes straight to the implementation and is never cached, because {@code this} is the
     * target object, not the proxy. Same trap applies to {@code @Transactional} and
     * {@code @Async}. It is the cost of aspects implemented by delegation rather than by
     * bytecode rewriting.
     */
    @Override
    @Cacheable(cacheNames = "instruments", unless = "#result == null")
    public InstrumentView findBySymbol(String symbol) {
        try {
            InstrumentView instrument = restClient.get()
                    .uri("/api/v1/instruments/{symbol}", symbol)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw NotFoundException.instrument(symbol);
                    })
                    .body(InstrumentView.class);

            if (instrument == null) {
                throw NotFoundException.instrument(symbol);
            }
            return instrument;
        } catch (NotFoundException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Reference data lookup failed for {}: {}", symbol, e.toString());
            throw new UpstreamUnavailableException("market-data-service",
                    "Could not load reference data for " + symbol, e);
        }
    }
}
