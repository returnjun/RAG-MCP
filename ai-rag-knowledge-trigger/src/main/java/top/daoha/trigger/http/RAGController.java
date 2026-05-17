package top.daoha.trigger.http;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.daoha.api.IRAGService;
import top.daoha.api.response.Response;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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

    @Resource
    private JdbcTemplate jdbcTemplate; // 注入 Spring 自带的 JdbcTemplate 从而直接查库


    /**
     * 查询标签列表：直接查库，确保 100% 数据一致。
     * 可以引入 Redis 缓存，但必须设置过期时间（如1小时），防止数据永久不一致。
     */
    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        try {
            // 方案 A：直接从 Redis 缓存中取（推荐配合 RBucket/RList 并设置过期时间）
            // 方案 B：直接查 PG 数据库（最安全），假设你的向量表名叫 vector_store
            // 注意：具体表名和元数据字段名需根据你的 PgVectorStore 配置调整，通常 Spring AI 默认元数据存在 metadata 字段的 JSON 中
            String sql = "SELECT DISTINCT metadata->>'knowledge' FROM vector_store WHERE metadata->>'knowledge' IS NOT NULL";
            List<String> tags = jdbcTemplate.queryForList(sql, String.class);

            return Response.<List<String>>builder()
                    .code("0000")
                    .info("调用成功")
                    .data(tags)
                    .build();
        } catch (Exception e) {
            log.error("查询知识库标签失败", e);
            return Response.<List<String>>builder().code("5000").info("系统错误").build();
        }
    }

    /**
     * http://localhost:8090/api/v1/rag/file/upload
     */
    @Override
    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    public Response<String> uploadFile(@RequestParam String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始: {}", ragTag);

        if (files == null || files.isEmpty()) {
            return Response.<String>builder().code("4000").info("上传文件不能为空").build();
        }

        try {
            for (MultipartFile file : files) {
                TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
                List<Document> documents = reader.get();

                // 1. 先为原始文档注入元数据绑定标签
                documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

                // 2. 再切分文档（这样切分出来的片段通常会自动继承或带上父文档的 Metadata）
                List<Document> split = tokenTextSplitter.apply(documents);
                // 保险起见，再次对切分后的片段确认注入
                split.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

                // 3. 写入向量数据库
                pgVectorStore.accept(split);
            }

            // 4. 如果你依然想用 Redis 做一个“快捷标签列表”供前端快速提示，改用 RSet
            // 哪怕这里网络挂了，由于上面查列表是走数据库的，顶多缓存没更新，不会影响业务
            RSet<String> tagSet = redissonClient.getSet("ragTagSet");
            tagSet.add(ragTag);

            log.info("上传知识库完成: {}", ragTag);
            return Response.<String>builder().code("0000").info("调用成功").build();

        } catch (Exception e) {
            log.error("上传知识库失败, tag: {}", ragTag, e);
            // 生产环境建议这里做更细致的异常拦截
            return Response.<String>builder().code("5000").info("知识库上传或解析失败: " + e.getMessage()).build();
        }
    }

    /**
     * http://localhost:8090/api/v1/rag/file/upload
     */
    @Override
    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    public Response<String> analyzeGitRepository(@RequestParam String reUrl, @RequestParam String userName, @RequestParam String token) throws Exception{

        String localPath = "./gitcloned";
        String repoProjectName = extractProjectName(reUrl);
        log.info("克隆地址:{}",new File(localPath).getAbsolutePath());

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(reUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName,token))
                .call();

        Files.walkFileTree(Paths.get(""),new SimpleFileVisitor<>(){

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String filePath = file.toString();

                // 1. 拦截空文件（直接解决当前的 ZeroByteFileException 报错）
                if (Files.size(file) == 0) {
                    log.warn("跳过空文件: {}", file);
                    return FileVisitResult.CONTINUE;
                }

                // 2. 拦截垃圾目录（千万不要把 .git、.idea、target 编译目录里的东西塞给大模型）
                if (filePath.contains(".git") || filePath.contains(".idea") || filePath.contains("target")) {
                    return FileVisitResult.CONTINUE;
                }

                // 3. 拦截非文本文件（推荐只允许特定后缀的代码或文档进入知识库）
                // 你可以根据需要自己加，比如 .html, .yml 等
                if (!filePath.endsWith(".java") && !filePath.endsWith(".md") && !filePath.endsWith(".xml") && !filePath.endsWith(".txt")) {
                    log.info("跳过不支持的后缀文件: {}", file);
                    return FileVisitResult.CONTINUE;
                }

                log.info("正在处理合法文件: {}", file);

                try {
                    PathResource resource = new PathResource(file);
                    TikaDocumentReader reader = new TikaDocumentReader(resource);
                    List<Document> documents = reader.get();
                    List<Document> split = tokenTextSplitter.apply(documents);

                    documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    split.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));

                    pgVectorStore.accept(split);
                    log.info("文件向量化上传完成: {}", file);

                } catch (Exception e) {
                    // 加上 try-catch，这样万一某个特殊文件解析失败，不会导致整个遍历程序崩溃
                    log.error("文件解析失败，已跳过: {}", file, e);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        git.close();

        RSet<String> tagSet = redissonClient.getSet("ragTagSet");
        tagSet.add(repoProjectName);
        log.info("上传知识库完成: {}", repoProjectName);

        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    public  String extractProjectName(String repoUrl){
        String [] parts = repoUrl.split("/");
        String part = parts[parts.length - 1];
        return part.replace(".git", "");
    }
}
