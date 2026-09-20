package dev.willcall.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.catalog.service.CatalogService;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Row labels have to stay unique and readable past Z, or a large venue gets two row As. */
class RowLabelTest {

  private static String label(int index) {
    try {
      Method method = CatalogService.class.getDeclaredMethod("rowLabel", int.class);
      method.setAccessible(true);
      return (String) method.invoke(null, index);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("labels run A..Z then AA..")
  void knownLabels() {
    assertThat(label(0)).isEqualTo("A");
    assertThat(label(25)).isEqualTo("Z");
    assertThat(label(26)).isEqualTo("AA");
    assertThat(label(27)).isEqualTo("AB");
    assertThat(label(51)).isEqualTo("AZ");
    assertThat(label(52)).isEqualTo("BA");
    assertThat(label(701)).isEqualTo("ZZ");
    assertThat(label(702)).isEqualTo("AAA");
  }

  @Test
  @DisplayName("the first thousand labels are all distinct")
  void labelsAreUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      assertThat(seen.add(label(i)))
          .as("label %s for index %s is a duplicate", label(i), i)
          .isTrue();
    }
  }

  @Property
  @Label("every label is non-empty upper-case letters only")
  void labelsAreReadable(@ForAll @IntRange(min = 0, max = 100_000) int index) {
    assertThat(label(index)).matches("[A-Z]+");
  }
}
