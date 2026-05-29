package com.capitec.fraud.engine;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves predicate ids to the corresponding {@link Predicate} bean.
 * Built at startup from Spring's collection-injection of all Predicate beans.
 * Fails fast if two predicates declare the same id.
 */
@Component
public class PredicateRegistry {

    private final Map<String, Predicate> byId;

    public PredicateRegistry(List<Predicate> predicates) {
        Map<String, Predicate> map = new HashMap<>();
        for (Predicate p : predicates) {
            Predicate existing = map.putIfAbsent(p.id(), p);
            if (existing != null) {
                throw new IllegalStateException("Duplicate predicate id: " + p.id()
                        + " (" + existing.getClass().getName() + " vs " + p.getClass().getName() + ")");
            }
        }
        this.byId = Map.copyOf(map);
    }

    public Predicate require(String id) {
        Predicate p = byId.get(id);
        if (p == null) {
            throw new IllegalArgumentException("Unknown predicate id: " + id + ". Known: " + byId.keySet());
        }
        return p;
    }

    public Set<String> knownIds() {
        return byId.keySet();
    }
}
