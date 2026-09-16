package in.postkaro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PostKaroApplication {
	public static void main(String[] args) {
		SpringApplication.run(PostKaroApplication.class, args);
	}
}
