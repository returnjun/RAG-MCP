package top.daoha.api;


import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;

public interface IAiService {

    ChatResponse generate(String model, String message);

    // 1. 普通流式对话加入 chatId
    Flux<ChatResponse> generateStream(@RequestParam("chatId") String chatId,
                                      @RequestParam("model") String model,
                                      @RequestParam("message") String message);

    // 2. RAG 流式对话加入 chatId
    Flux<ChatResponse> generateStreamRag(@RequestParam("chatId") String chatId,
                                         @RequestParam("model") String model,
                                         @RequestParam("ragTag") String ragTag,
                                         @RequestParam("message") String message);
}
