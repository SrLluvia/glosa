package dev.glosa.core.collection;

import dev.glosa.core.error.DuplicateResourceException;
import dev.glosa.core.error.ResourceNotFoundException;
import dev.glosa.core.tenant.TenantContext;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collection use cases.
 *
 * <p>Authorization sits here rather than on the controller, so a rule holds for
 * every caller of the use case and not just for one HTTP route.
 */
@Service
public class DocumentCollectionService {

    private final DocumentCollectionRepository repository;

    public DocumentCollectionService(DocumentCollectionRepository repository) {
        this.repository = repository;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR')")
    @Transactional
    public DocumentCollection create(String name) {
        // Taken from the request's verified token, never from the request body.
        UUID tenantId = TenantContext.requireTenantId();

        if (repository.existsByName(name)) {
            throw new DuplicateResourceException("A collection named '" + name + "' already exists");
        }

        try {
            return repository.saveAndFlush(new DocumentCollection(tenantId, name));
        } catch (DataIntegrityViolationException e) {
            // The check above is a courtesy for the common case; the unique
            // constraint is what actually prevents a duplicate under a race.
            throw new DuplicateResourceException("A collection named '" + name + "' already exists");
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public Page<DocumentCollection> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER')")
    @Transactional(readOnly = true)
    public DocumentCollection get(UUID id) {
        // A collection belonging to another tenant is invisible to this query, so
        // it is reported as missing rather than forbidden. Saying "forbidden"
        // would confirm the id exists somewhere, which is a disclosure in itself.
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No collection with id " + id));
    }
}
