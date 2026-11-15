package com.exepex.stratum.datajpa.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.keyvalue.repository.support.KeyValueRepositoryFactoryBean;

@DisplayName("JpaRepositoryDefinitions finds the JPA repository interfaces in the bean definitions")
class JpaRepositoryDefinitionsTest {

  private final JpaRepositoryDefinitions definitions = new JpaRepositoryDefinitions();

  /** The application's bean definitions. */
  private final DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

  /** Defines a bean of a class, whose first constructor argument is the given value. */
  private void define(String beanName, @Nullable Class<?> type, @Nullable Object argument) {
    var definition = new RootBeanDefinition();
    if (type != null) {
      definition.setBeanClass(type);
    }
    if (argument != null) {
      definition.getConstructorArgumentValues().addIndexedArgumentValue(0, argument);
    }
    registry.registerBeanDefinition(beanName, definition);
  }

  /** Returns the interfaces found. */
  private Object[] found() {
    return definitions.find(registry, Thread.currentThread().getContextClassLoader()).toArray();
  }

  @Nested
  @DisplayName("a JPA repository's factory bean")
  class JpaFactory {

    @Test
    @DisplayName("gives its interface, whether the argument is the class or its name")
    void givesInterface() {
      define("byClass", JpaRepositoryFactoryBean.class, Callable.class);
      define("byName", JpaRepositoryFactoryBean.class, Supplier.class.getName());

      assertThat(found()).containsExactlyInAnyOrder(Callable.class, Supplier.class);
    }

    @Test
    @DisplayName("with no interface argument gives nothing")
    void noInterface() {
      define("empty", JpaRepositoryFactoryBean.class, null);
      define("number", JpaRepositoryFactoryBean.class, 1);

      assertThat(found()).isEmpty();
    }
  }

  @Nested
  @DisplayName("any other definition")
  class OtherDefinitions {

    @Test
    @DisplayName("of another Spring Data module, such as key-value, is left out")
    void otherModuleLeftOut() {
      define("keyValue", KeyValueRepositoryFactoryBean.class, Callable.class);

      assertThat(found()).isEmpty();
    }

    @Test
    @DisplayName("of no class, or of a plain bean, is left out")
    void plainLeftOut() {
      define("noClass", null, Callable.class);
      define("plain", Object.class, Callable.class);

      assertThat(found()).isEmpty();
    }
  }
}
