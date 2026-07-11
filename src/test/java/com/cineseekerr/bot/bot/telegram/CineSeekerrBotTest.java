package com.cineseekerr.bot.bot.telegram;

import com.cineseekerr.bot.bot.ConversationHandler;
import com.cineseekerr.bot.config.CineSeekerrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CineSeekerrBotTest {

    private static final long ALLOWED_CHAT = 123L;
    private static final long STRANGER_CHAT = 666L;

    @Mock
    private ConversationHandler handler;
    @Mock
    private TelegramMessenger messenger;

    private CineSeekerrBot bot;

    @BeforeEach
    void setUp() {
        CineSeekerrProperties properties = new CineSeekerrProperties(
                new CineSeekerrProperties.Telegram("token", Set.of(ALLOWED_CHAT)),
                null, null, null, null);
        bot = new CineSeekerrBot(handler, messenger, properties);
    }

    private static Update textUpdate(long chatId, String text) {
        Message message = new Message();
        message.setChat(Chat.builder().id(chatId).type("private").build());
        message.setText(text);
        message.setMessageId(7);
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static Update callbackUpdate(long chatId, String data) {
        Message message = new Message();
        message.setChat(Chat.builder().id(chatId).type("private").build());
        message.setMessageId(7);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb1");
        callback.setData(data);
        callback.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    @Test
    void textFromAllowedChatIsDispatched() {
        bot.consume(textUpdate(ALLOWED_CHAT, "dune"));
        verify(handler).onTextMessage(ALLOWED_CHAT, "dune");
    }

    @Test
    void callbackFromAllowedChatIsDispatched() {
        bot.consume(callbackUpdate(ALLOWED_CHAT, "movie:0"));
        verify(handler).onCallback(ALLOWED_CHAT, 7, "cb1", "movie:0");
    }

    @Test
    void updatesFromUnknownChatsAreSilentlyIgnored() {
        bot.consume(textUpdate(STRANGER_CHAT, "dune"));
        bot.consume(callbackUpdate(STRANGER_CHAT, "movie:0"));
        verifyNoInteractions(handler);
        verifyNoInteractions(messenger);
    }

    @Test
    void handlerCrashDoesNotPropagateAndUserIsInformed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(handler).onTextMessage(ALLOWED_CHAT, "dune");
        bot.consume(textUpdate(ALLOWED_CHAT, "dune"));
        verify(messenger).sendHtml(org.mockito.ArgumentMatchers.eq(ALLOWED_CHAT),
                org.mockito.ArgumentMatchers.contains("Errore inatteso"),
                org.mockito.ArgumentMatchers.isNull());
    }
}
