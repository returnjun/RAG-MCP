package top.daoha.trigger.http;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import top.daoha.api.IAiService;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/openai")
public class OpenAiController implements IAiService {

    private final ChatClient chatClient;

    @Resource
    private PgVectorStore pgVectorStore;

    // 构造函数注入，利用 ChatClient.builder 绑定记忆顾问
    public OpenAiController(OpenAiChatModel openAiChatModel, ChatMemory chatMemory) {
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();
    }

    @RequestMapping(value = "generate", method = RequestMethod.GET)
    @Override
    public ChatResponse generate(@RequestParam String model, @RequestParam String message) {
        return this.chatClient.prompt()
                .user(message)
                .options(OpenAiChatOptions.builder().model(model).build())
                .call()
                .chatResponse();
    }

    @RequestMapping(value = "generate_stream", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public Flux<ChatResponse> generateStream(@RequestParam String chatId, @RequestParam String model, @RequestParam String message) {
        return this.chatClient.prompt()
                .user(message)
                .options(OpenAiChatOptions.builder().model(model).build())
                // 核心：绑定当前的会话 ID，Spring AI 会自动存取历史消息
                .advisors(a -> a.param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .stream()
                .chatResponse();
    }

    @RequestMapping(value = "generate_stream_rag", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public Flux<ChatResponse> generateStreamRag(@RequestParam String chatId,
                                                @RequestParam String model,
                                                @RequestParam String ragTag,
                                                @RequestParam String message) {
        String SYSTEM_PROMPT = """
            Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
            If unsure, simply state that you don't know.
            Another thing you need to note is that your reply must be in Chinese!
            
            DOCUMENTS:
            {documents}
            """;

        // 向量检索
        SearchRequest request = SearchRequest.builder()
                .query(message)
                .topK(5)
                .filterExpression("knowledge == '" + ragTag + "'")
                .build();
        List<org.springframework.ai.document.Document> documents = pgVectorStore.similaritySearch(request);
        String documentCollectors = documents.stream()
                .map(org.springframework.ai.document.Document::getText)
                .collect(Collectors.joining("\n\n"));

        return this.chatClient.prompt()
                // 使用 ChatClient 的 system 链式方法注入 RAG 提示词
                .system(sp -> sp.text(SYSTEM_PROMPT).param("documents", documentCollectors))
                .user(message)
                .options(OpenAiChatOptions.builder().model(model).build())
                // 同时享有上下文记忆能力
                .advisors(a -> a.param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .stream()
                .chatResponse();
    }
}