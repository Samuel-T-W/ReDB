package operators;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import storage.GenericRecord;

public class Selection extends AbstractOperator {

    private final Operator child;
    private final Predicate<GenericRecord> predicate;
    // Predicates are opaque lambdas, so a human-readable description of the
    // condition has to be supplied by the call site; null keeps a plain label.
    private final @Nullable String description;

    public Selection(Operator child, Predicate<GenericRecord> predicate) {
        this(child, predicate, null);
    }

    public Selection(Operator child, Predicate<GenericRecord> predicate, @Nullable String description) {
        this.child = Objects.requireNonNull(child, "child");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        this.description = description;
    }

    @Override
    protected void doOpen() {
        child.open();
    }

    @Override
    protected GenericRecord fetchNext() {
        GenericRecord record;
        while ((record = child.next()) != null) {
            if (predicate.test(record)) {
                return record;
            }
        }
        return null;
    }

    @Override
    protected void doClose() {
        child.close();
    }

    @Override
    public List<Operator> children() {
        return List.of(child);
    }

    @Override
    public String label() {
        return description == null ? "Selection" : "Selection: " + description;
    }
}
