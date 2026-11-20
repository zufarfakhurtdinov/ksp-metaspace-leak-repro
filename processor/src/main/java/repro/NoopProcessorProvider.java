package repro;

import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import java.util.List;

public class NoopProcessorProvider implements SymbolProcessorProvider {
  @Override
  public SymbolProcessor create(SymbolProcessorEnvironment environment) {
    return resolver -> List.of();
  }
}
