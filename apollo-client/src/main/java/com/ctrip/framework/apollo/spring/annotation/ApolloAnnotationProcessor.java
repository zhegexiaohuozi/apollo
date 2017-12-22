package com.ctrip.framework.apollo.spring.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ctrip.framework.apollo.spring.auto.SpringValue;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigChangeListener;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.google.common.base.Preconditions;

/**
 * Apollo Annotation Processor for Spring Application
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public class ApolloAnnotationProcessor implements BeanPostProcessor, PriorityOrdered {
  private Pattern pattern = Pattern.compile("\\$\\{(.*)\\}:?(.*)");
  private static Multimap<String, SpringValue> monitor = LinkedListMultimap.create();
  private Logger logger = LoggerFactory.getLogger(ApolloAnnotationProcessor.class);

  public static Multimap<String, SpringValue> monitor() {
    return monitor;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
    Class clazz = bean.getClass();
    processFields(bean, findAllField(clazz));
    processMethods(bean, findAllMethod(clazz));
    return bean;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    return bean;
  }

  private void processFields(Object bean, List<Field> declaredFields) {
    for (Field field : declaredFields) {
      // regist @Value on field
      registSpringValueOnField(bean, field);

      ApolloConfig annotation = AnnotationUtils.getAnnotation(field, ApolloConfig.class);
      if (annotation == null) {
        continue;
      }

      Preconditions.checkArgument(Config.class.isAssignableFrom(field.getType()),
          "Invalid type: %s for field: %s, should be Config", field.getType(), field);

      String namespace = annotation.value();
      Config config = ConfigService.getConfig(namespace);

      ReflectionUtils.makeAccessible(field);
      ReflectionUtils.setField(field, bean, config);
    }
  }

  private void processMethods(final Object bean, List<Method> declaredMethods) {
    for (final Method method : declaredMethods) {
      //regist @Value on method
      registSpringValueOnMethod(bean, method);

      ApolloConfigChangeListener annotation = AnnotationUtils.findAnnotation(method, ApolloConfigChangeListener.class);
      if (annotation == null) {
        continue;
      }

      Class<?>[] parameterTypes = method.getParameterTypes();
      Preconditions.checkArgument(parameterTypes.length == 1,
          "Invalid number of parameters: %s for method: %s, should be 1", parameterTypes.length, method);
      Preconditions.checkArgument(ConfigChangeEvent.class.isAssignableFrom(parameterTypes[0]),
          "Invalid parameter type: %s for method: %s, should be ConfigChangeEvent", parameterTypes[0], method);

      ReflectionUtils.makeAccessible(method);
      String[] namespaces = annotation.value();
      for (String namespace : namespaces) {
        Config config = ConfigService.getConfig(namespace);

        config.addChangeListener(new ConfigChangeListener() {
          @Override
          public void onChange(ConfigChangeEvent changeEvent) {
            ReflectionUtils.invokeMethod(method, bean, changeEvent);
          }
        });
      }
    }
  }

  @Override
  public int getOrder() {
    //make it as late as possible
    return Ordered.LOWEST_PRECEDENCE;
  }
  private void registSpringValueOnMethod(Object bean, Method method) {
    Value value = method.getAnnotation(Value.class);
    if (value == null) {
      return;
    }
    Matcher matcher = pattern.matcher(value.value());
    if (matcher.matches()) {
      String key = matcher.group(1);
      monitor.put(key, SpringValue.create(bean, method));
      logger.info("Listening apollo key = {}", key);
    }
  }

  private void registSpringValueOnField(Object bean, Field field) {
    Value value = field.getAnnotation(Value.class);
    if (value == null) {
      return;
    }
    Matcher matcher = pattern.matcher(value.value());
    if (matcher.matches()) {
      String key = matcher.group(1);
      monitor.put(key, SpringValue.create(bean, field));
      logger.info("Listening apollo key = {}", key);
    }
  }

  private List<Field> findAllField(Class clazz) {
    final List<Field> res = new LinkedList<>();
    ReflectionUtils.doWithFields(clazz, new ReflectionUtils.FieldCallback() {
      @Override
      public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
        res.add(field);
      }
    });
    return res;
  }

  private List<Method> findAllMethod(Class clazz) {
    final List<Method> res = new LinkedList<>();
    ReflectionUtils.doWithMethods(clazz, new ReflectionUtils.MethodCallback() {
      @Override
      public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
        res.add(method);
      }
    });
    return res;
  }
}
