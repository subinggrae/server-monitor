package kr.co.unidocs.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MonitoringManagerApplication {

	public static void main(String[] args) {
		SpringApplication.run(MonitoringManagerApplication.class, args);
	}

}
