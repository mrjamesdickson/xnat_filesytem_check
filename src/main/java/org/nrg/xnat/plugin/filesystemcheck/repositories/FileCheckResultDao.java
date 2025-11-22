package org.nrg.xnat.plugin.filesystemcheck.repositories;

import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.nrg.xnat.plugin.filesystemcheck.entities.FileCheckResultEntity;
import org.springframework.stereotype.Repository;

import javax.persistence.Query;
import java.util.List;

@Repository
public class FileCheckResultDao extends AbstractHibernateDAO<FileCheckResultEntity> {

    public List<FileCheckResultEntity> findByCheckId(String checkId, int page, int pageSize) {
        return getSession()
                .createQuery("FROM FileCheckResultEntity WHERE checkId = :checkId ORDER BY id",
                        FileCheckResultEntity.class)
                .setParameter("checkId", checkId)
                .setFirstResult(page * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    public List<FileCheckResultEntity> findByCheckIdAndStatus(String checkId, String status, int page, int pageSize) {
        return getSession()
                .createQuery("FROM FileCheckResultEntity WHERE checkId = :checkId AND status = :status ORDER BY id",
                        FileCheckResultEntity.class)
                .setParameter("checkId", checkId)
                .setParameter("status", status)
                .setFirstResult(page * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    public long countByCheckId(String checkId) {
        return getSession()
                .createQuery("SELECT COUNT(*) FROM FileCheckResultEntity WHERE checkId = :checkId", Long.class)
                .setParameter("checkId", checkId)
                .getSingleResult();
    }

    public long countByCheckIdAndStatus(String checkId, String status) {
        return getSession()
                .createQuery("SELECT COUNT(*) FROM FileCheckResultEntity WHERE checkId = :checkId AND status = :status", Long.class)
                .setParameter("checkId", checkId)
                .setParameter("status", status)
                .getSingleResult();
    }

    public void deleteByCheckId(String checkId) {
        getSession()
                .createQuery("DELETE FROM FileCheckResultEntity WHERE checkId = :checkId")
                .setParameter("checkId", checkId)
                .executeUpdate();
    }

    public void saveBatch(List<FileCheckResultEntity> entities) {
        for (int i = 0; i < entities.size(); i++) {
            getSession().persist(entities.get(i));
            if (i % 50 == 0) {  // Flush every 50 records
                getSession().flush();
                getSession().clear();
            }
        }
    }
}
