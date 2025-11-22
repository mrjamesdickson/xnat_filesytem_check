package org.nrg.xnat.plugin.filesystemcheck.repositories;

import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class FilesystemCheckDao extends AbstractHibernateDAO<FilesystemCheckEntity> {

    public FilesystemCheckEntity findByCheckId(String checkId) {
        return findByUniqueProperty("checkId", checkId);
    }

    public List<FilesystemCheckEntity> findByUsername(String username) {
        return findByProperty("username", username);
    }

    public List<FilesystemCheckEntity> findByStatus(String status) {
        return findByProperty("status", status);
    }

    public List<FilesystemCheckEntity> findActiveChecks() {
        return getSession()
                .createQuery("FROM FilesystemCheckEntity WHERE status IN ('queued', 'running') ORDER BY startedAt DESC",
                        FilesystemCheckEntity.class)
                .getResultList();
    }

    public List<FilesystemCheckEntity> findRecentCompleted(int limit) {
        return getSession()
                .createQuery("FROM FilesystemCheckEntity WHERE status IN ('completed', 'failed', 'cancelled') ORDER BY completedAt DESC",
                        FilesystemCheckEntity.class)
                .setMaxResults(limit)
                .getResultList();
    }

    public void deleteOlderThan(Instant cutoffDate) {
        getSession()
                .createQuery("DELETE FROM FilesystemCheckEntity WHERE completedAt < :cutoffDate AND status IN ('completed', 'failed', 'cancelled')")
                .setParameter("cutoffDate", cutoffDate)
                .executeUpdate();
    }
}
