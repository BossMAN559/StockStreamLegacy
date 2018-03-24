package network.gateway.twitch;


import application.Config;
import cache.InfoMessageCache;
import cache.InstrumentCache;
import cache.PlayerScoreCache;
import computer.TimeComputer;
import data.Quote;
import environment.OBSManager;
import environment.android.AndroidManager;
import environment.jukebox.Jukebox;
import exception.RobinhoodException;
import logic.MarketClock;
import logic.Scheduler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import network.gateway.broker.Broker;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.pircbotx.hooks.Event;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.UnknownEvent;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class Responder extends ListenerAdapter {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public class MessageEventResponse {
        private MessageEvent messageEvent;
        private String sender;
        private String response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public class EventResponse {
        private Event event;
        private String sender;
        private String response;
    }

    private static final String WHISPER_KEY = "WHISPER";
    private static final String BOT_NAME = "stockstream";

    private static long lastChannelMessageSent = new DateTime().getMillis();
    private static long lastPrivateMessageSent = new DateTime().getMillis();

    private Queue<MessageEventResponse> messageEventResponseQueue = new ConcurrentLinkedQueue<>();
    private Queue<EventResponse> eventResponseQueue = new ConcurrentLinkedQueue<>();

    @Autowired
    private PlayerScoreCache playerScoreCache;

    @Autowired
    private InfoMessageCache infoMessageCache;

    @Autowired
    private MarketClock marketClock;

    @Autowired
    private AndroidManager androidManager;

    @Autowired
    private Jukebox jukebox;

    @Autowired
    private OBSManager obsManager;

    @Autowired
    private TimeComputer timeComputer;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private Broker broker;

    @Autowired
    private InstrumentCache instrumentCache;


    @PostConstruct
    public void init() {
        this.scheduler.scheduleJob(this::tickMessageQueues, 5000, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onEvent(final Event event) throws Exception {
        super.onEvent(event);
    }

    @Override
    public void onPrivateMessage(final PrivateMessageEvent event) throws Exception {
        super.onPrivateMessage(event);
        final String eventLine = event.getMessage();
        if (StringUtils.isEmpty(eventLine)) {
            return;
        }

        if (event.getUser() == null) {
            return;
        }

        processWhisper(event, event.getUser().getNick(), eventLine);
    }

    @Override
    public void onUnknown(final UnknownEvent event) throws Exception {
        super.onUnknown(event);
        final String eventLine = event.getLine();
        if (StringUtils.isEmpty(eventLine)) {
            return;
        }
        if (eventLine.contains(WHISPER_KEY)) {
            final String[] messageParts = eventLine.split(BOT_NAME);
            if (messageParts.length != 2) {
                log.warn("Unable to process whisper event {}", event);
                return;
            }

            final String messageData = messageParts[1].substring(2);
            final String sender = eventLine.split("!")[0].substring(1);

            processWhisper(event, sender, messageData);
        }
    }

    private void processWhisper(final Event event, final String sender, final String whisper) {

    }

    @Override
    public void onMessage(final MessageEvent event) {
        final String eventMessage = event.getMessage();
        if (StringUtils.isEmpty(eventMessage)) {
            return;
        }

        if (!eventMessage.startsWith("!")) {
            return;
        }

        if (event.getUser() == null) {
            return;
        }

        final String sender = event.getUser().getNick();


        if (instrumentCache.getValidSymbols().contains(eventMessage.substring(1).toUpperCase())) {

            final String symbol = eventMessage.substring(1).toUpperCase();
            final StringBuilder stringBuilder = new StringBuilder();

            try {
                final Quote stockQuote = new Quote(broker.getQuote(symbol));

                stringBuilder.append(String.format(" %s last price is around $%.2f. Bid [%s @ $%.2f] Ask [%s @ $%.2f]",
                                                   symbol, stockQuote.getMostRecentPrice(), stockQuote.getBidSize(), stockQuote.getBidPrice(),
                                                   stockQuote.getAskSize(), stockQuote.getAskPrice()));

                final String changeOperand = stockQuote.getPercentChange() < 0 ? "" : "+";
                stringBuilder.append(String.format(" Today %s%.2f%%.", changeOperand, stockQuote.getPercentChange()));
            } catch (final RobinhoodException e) {
                log.warn("No quote for symbol {}", symbol, e);
            }

            stringBuilder.append(" https://finance.yahoo.com/quote/").append(symbol);

            messageEventResponseQueue.add(new MessageEventResponse(event, sender, stringBuilder.toString()));
        }

        /*if ("!score".equalsIgnoreCase(eventMessage)) {
            final String response = String.valueOf(this.playerScoreCache.getScore(sender));
            messageEventResponseQueue.add(new MessageEventResponse(event, sender, response));
        }

        if (eventMessage.equalsIgnoreCase("!discord")) {
            messageEventResponseQueue.add(new MessageEventResponse(event, sender, Config.DISCORD_URL));
            return;
        }

        if (eventMessage.equalsIgnoreCase("!stream")) {
            Optional<DateTime> nextMarketOpen = this.marketClock.getNextEventDate(MarketEvent.MARKET_OPEN_EVENT);
            if (!nextMarketOpen.isPresent()) {
                return;
            }
            final String response = String.format("Next stream starts when the market opens at %s", nextMarketOpen.get());
            messageEventResponseQueue.add(new MessageEventResponse(event, sender, response));
            return;
        }*/

        if (Config.ADMINS_TWITCH.contains(sender.toLowerCase())) {

            String commandOutput = "";
            switch (eventMessage) {
                case "!restart_app":
                    this.androidManager.restartRobinhoodApp();
                    break;
                case "!reset_screen":
                    this.androidManager.resetScreen();
                    break;
                case "!start_stream":
                    this.obsManager.startOBSStream();
                    break;
                case "!stop_stream":
                    this.obsManager.stopOBSStream();
                    break;
            }

            if (!StringUtils.isEmpty(commandOutput)) {
                messageEventResponseQueue.add(new MessageEventResponse(event, sender, commandOutput));
                return;
            }
        }

        /*if ((eventMessage.equalsIgnoreCase("!music") || eventMessage.equalsIgnoreCase("!song"))) {
            final String trackUrl = this.jukebox.getCurrentTrackURL();
            messageEventResponseQueue.add(new MessageEventResponse(event, sender, trackUrl));
            return;
        }*/

        final Optional<String> urlForCommand = this.infoMessageCache.getURLForId(event.getMessage());
        urlForCommand.ifPresent(url -> messageEventResponseQueue.add(new MessageEventResponse(event, sender, url)));
    }

    private void tickMessageQueues() {
        if (new DateTime().minusMillis(200).isAfter(lastPrivateMessageSent)) {
            respondWhisper(eventResponseQueue.poll());
        }
        if (new DateTime().minusSeconds(2).isAfter(lastChannelMessageSent)) {
            respond(messageEventResponseQueue.poll());
        }
    }

    private void respondWhisper(final EventResponse eventResponse) {
        if (new DateTime().minusMillis(200).isBefore(lastPrivateMessageSent) || eventResponse == null) {
            return;
        }

        eventResponse.getEvent().respond(String.format("PRIVMSG #jtv :/w %s %s\r\n", eventResponse.getSender(), eventResponse.getResponse()));
    }

    private void respond(final MessageEventResponse messageEventResponse) {
        if (new DateTime().minusSeconds(2).isBefore(lastChannelMessageSent) || messageEventResponse == null) {
            return;
        }

        lastChannelMessageSent = new DateTime().getMillis();
        messageEventResponse.getMessageEvent().respondChannel(String.format("@%s %s", messageEventResponse.getSender(), messageEventResponse.getResponse()));
    }

}