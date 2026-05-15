package top.daoha.trigger.http;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.daoha.api.IRAGService;
import top.daoha.api.response.Response;

import java.util.List;
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag")
public class RAGController implements IRAGService {

    @Resource
    private OllamaChatModel ollamaChatModel; // 注入这个新类

    @Resource
    private SimpleVectorStore simpleVectorStore;

    @Resource
    private TokenTextSplitter tokenTextSplitter;

    @Resource
    private PgVectorStore pgVectorStore;

    @Resource
    private RedissonClient redissonClient;


    @RequestMapping(value = "query_rag_tag_list",method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");

        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @Override
    public Response<String> uploadFile(@RequestParam String ragTag,@RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始:{}", ragTag);
        for (MultipartFile file : files) {
            TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
            List<Document> documents = reader.get();
            List<Document> split = tokenTextSplitter.apply(documents);

            documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            split.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

            pgVectorStore.accept(split);

            RList<String> elements = redissonClient.getList("ragTag");
            if(!elements.contains(ragTag)){
                elements.add(ragTag);
            }
        }

        log.info("上传知识库完成:{}", ragTag);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }
}
