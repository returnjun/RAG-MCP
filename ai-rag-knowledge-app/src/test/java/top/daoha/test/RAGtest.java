package top.daoha.test;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class RAGtest {

    @Resource
    private OllamaChatModel ollamaChatModel; // 注入这个新类

    @Resource
    private SimpleVectorStore simpleVectorStore;

    @Resource
    private TokenTextSplitter  tokenTextSplitter;

    @Resource
    private PgVectorStore pgVectorStore;

    @Test
    public void test() {
        log.info("test");
        TikaDocumentReader reader = new TikaDocumentReader("./data/file.text");
        List<Document> documents = reader.get();
        List<Document> split = tokenTextSplitter.apply(documents);

        documents.forEach(doc -> doc.getMetadata().put("knowledge", "知识库"));
        split.forEach(doc -> doc.getMetadata().put("knowledge", "知识库"));

        pgVectorStore.accept(documents);
        log.info("上传完成");
    }


    @Test
    public void test2() {
        log.info("test2");
        String message = "王大挂那一年出生";
        String SYSTEM_PROMPT = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in Chinese!
                DOCUMENTS:
                    {documents}
                """;

        // 1. 修复 SearchRequest，使用 1.0.0-M6 的 Builder 语法
        SearchRequest request = SearchRequest.builder()
                .query(message)
                .topK(5)
                .filterExpression("knowledge == '知识库'")
                .build();

        List<Document> documents = pgVectorStore.similaritySearch(request);

        // 2. 修复 getContent() 找不到的问题，改为 getText()，并建议用换行符连接
        String documentsCollectors = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT)
                .createMessage(Map.of("documents", documentsCollectors));

        List<Message> messages = new ArrayList<>();
        // 建议顺序：系统提示词 (包含背景知识) 放在最前面，用户的实际问题放在后面
        messages.add(ragMessage);
        messages.add(new UserMessage(message));

        // 3. 修复隐藏 Bug：将组装好的 messages 列表传给 Prompt，而不是传原本的 message 字符串
        Prompt prompt = new Prompt(messages, OllamaOptions.builder().model("deepseek-r1:1.5b").build());

        ChatResponse chatResponse = ollamaChatModel.call(prompt);

        log.info("测试结果:{}", JSON.toJSONString(chatResponse));
    }
}
