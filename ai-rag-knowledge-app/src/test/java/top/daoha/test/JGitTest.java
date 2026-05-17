package top.daoha.test;

import jakarta.annotation.Resource;
import org.apache.commons.io.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.PathResource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class JGitTest {
    @Resource
    private OllamaChatModel ollamaChatModel; // 注入这个新类

    @Resource
    private SimpleVectorStore simpleVectorStore;

    @Resource
    private TokenTextSplitter tokenTextSplitter;

    @Resource
    private PgVectorStore pgVectorStore;

    @Test
    public void test() throws Exception {
        String repoURL = "https://gitee.com/zjhdk/rag-mcp.git";
        String username = "zjhdk";
        String password = "21188a65b33c34f87216fa28a7756caf";

        String localPath = "./cloned-repo";
        log.info("clone repo to {}", localPath);

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoURL)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username,password))
                .call();
        git.close();
    }

    @Test
    public void test_file() throws Exception {
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

                    documents.forEach(doc -> doc.getMetadata().put("knowledge", "知识库"));
                    split.forEach(doc -> doc.getMetadata().put("knowledge", "知识库"));

                    pgVectorStore.accept(split);
                    log.info("文件向量化上传完成: {}", file);

                } catch (Exception e) {
                    // 加上 try-catch，这样万一某个特殊文件解析失败，不会导致整个遍历程序崩溃
                    log.error("文件解析失败，已跳过: {}", file, e);
                }

                return FileVisitResult.CONTINUE;
            }
        });

    }
}
