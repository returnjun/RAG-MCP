package top.daoha.trigger.http;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import top.daoha.api.IAiService;

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/ollama")
public class OllamaController implements IAiService {

    @Resource
    private OllamaChatModel ollamaChatModel; // 注入这个新类

    /**
     * http://localhost:8090/api/v1/ollama/generate?model=deepseek-r1:1.5b&message=1+1
     */
    @RequestMapping(value = "generate",method = RequestMethod.GET)
    @Override
    public ChatResponse generate(@RequestParam String model, @RequestParam String message) {
        return ollamaChatModel.call(new Prompt(message, OllamaOptions.builder().model(model).build()));
    }

    /**
     * http://localhost:8090/api/v1/ollama/generate_stream?model=deepseek-r1:1.5b&message=hi
     */
    @RequestMapping(value = "generate_stream",method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public Flux<ChatResponse> generateStream(@RequestParam String model,@RequestParam String message) {
        return ollamaChatModel.stream(new Prompt(message, OllamaOptions.builder().model(model).build()));
    }
}
