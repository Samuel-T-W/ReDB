package operators;

import java.util.Objects;
import java.util.function.Predicate;
import storage.GenericRecord;

public class Selection implements Operator {

    private final Operator child;
    private final Predicate<GenericRecord> predicate;

    public Selection(Operator child, Predicate<GenericRecord> predicate) {
        this.child = Objects.requireNonNull(child, "child");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public GenericRecord next() {
        GenericRecord record;
        while ((record = child.next()) != null) {
            if (predicate.test(record)) {
                return record;
            }
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
    }
}
