package com.delivery.backend.modules.field;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Exécuteur dédié à la collecte terrain.
 *
 * <p>Un seul thread et une file bornée : une écriture Postgres lente ne doit
 * jamais retarder le broadcast GPS, et une panne de la base ne doit pas créer
 * une file infinie. Si la file est pleine, la tâche est rejetée et le recorder
 * l'ignore : perdre une trace est moins grave que perdre une position.
 */
@Configuration
@EnableScheduling
public class FieldCollectionConfig {

    @Bean(name = "fieldCollectionExecutor")
    public Executor fieldCollectionExecutor(FieldCollectionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("field-collection-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
