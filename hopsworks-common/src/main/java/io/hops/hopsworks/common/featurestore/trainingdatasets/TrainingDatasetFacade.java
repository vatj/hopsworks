/*
 * This file is part of Hopsworks
 * Copyright (C) 2019, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package io.hops.hopsworks.common.featurestore.trainingdatasets;

import com.google.common.collect.Lists;
import io.hops.hopsworks.common.dao.AbstractFacade;
import io.hops.hopsworks.common.featurestore.trainingdatasets.external.ExternalTrainingDatasetFacade;
import io.hops.hopsworks.common.featurestore.trainingdatasets.hopsfs.HopsfsTrainingDatasetFacade;
import io.hops.hopsworks.common.util.Settings;
import io.hops.hopsworks.exceptions.FeaturestoreException;
import io.hops.hopsworks.persistence.entity.featurestore.Featurestore;
import io.hops.hopsworks.persistence.entity.featurestore.featureview.FeatureView;
import io.hops.hopsworks.persistence.entity.featurestore.trainingdataset.TrainingDataset;
import io.hops.hopsworks.restutils.RESTCodes;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A facade for the training_dataset table in the Hopsworks database, use this interface when performing database
 * operations against the table.
 */
@Stateless
public class TrainingDatasetFacade extends AbstractFacade<TrainingDataset> {
  private static final Logger LOGGER = Logger.getLogger(TrainingDatasetFacade.class.getName());

  @PersistenceContext(unitName = "kthfsPU")
  private EntityManager em;
  
  @EJB
  private Settings settings;
  @EJB
  private HopsfsTrainingDatasetFacade hopsfsTrainingDatasetFacade;
  @EJB
  private ExternalTrainingDatasetFacade externalTrainingDatasetFacade;

  public TrainingDatasetFacade() {
    super(TrainingDataset.class);
  }

  /**
   * Retrieves a particular TrainingDataset given its Id from the database
   *
   * @param id id of the TrainingDataset
   * @return a single TrainingDataset entity
   */
  public TrainingDataset findById(Integer id) {
    try {
      return em.createNamedQuery("TrainingDataset.findById", TrainingDataset.class)
          .setParameter("id", id).getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }
  
  public List<TrainingDataset> findByIds(List<Integer> ids) {
    if (ids.size() > settings.getSQLMaxSelectIn()) {
      List<TrainingDataset> result = new ArrayList<>();
      for(List<Integer> partition : Lists.partition(ids, settings.getSQLMaxSelectIn())) {
        TypedQuery<TrainingDataset> query =
          em.createNamedQuery("TrainingDataset.findByIds", TrainingDataset.class);
        query.setParameter("ids", partition);
        result.addAll(query.getResultList());
      }
      return result;
    } else {
      TypedQuery<TrainingDataset> query =
        em.createNamedQuery("TrainingDataset.findByIds", TrainingDataset.class);
      query.setParameter("ids", ids);
      return query.getResultList();
    }
  }
  
  /**
   * Retrieves a particular trainingDataset given its Id and featurestore from the database
   *
   * @param id           id of the trainingDataset
   * @param featurestore featurestore of the trainingDataset
   * @return a single TrainingDataset entity
   */
  public Optional<TrainingDataset> findByIdAndFeaturestore(Integer id, Featurestore featurestore) {
    try {
      return Optional.of(em.createNamedQuery("TrainingDataset.findByFeaturestoreAndId", TrainingDataset.class)
          .setParameter("featurestore", featurestore)
          .setParameter("id", id)
          .getSingleResult());
    } catch (NoResultException e) {
      return Optional.empty();
    }
  }

  /**
   * Retrieves a list of trainingDataset (different versions) given their name and feature store from the database
   *
   * @param name name of the trainingDataset
   * @param featurestore featurestore of the trainingDataset
   * @return a single TrainingDataset entity
   */
  public List<TrainingDataset> findByNameAndFeaturestore(String name, Featurestore featurestore) {
    return em.createNamedQuery("TrainingDataset.findByFeaturestoreAndName", TrainingDataset.class)
          .setParameter("featurestore", featurestore)
          .setParameter("name", name)
          .getResultList();
  }

  /**
   * Retrieves a list of trainingDataset (different versions) given their name and feature store from the database
   *
   * @param name name of the trainingDataset
   * @param featurestore featurestore of the trainingDataset
   * @return a single TrainingDataset entity
   */
  public List<TrainingDataset> findByNameAndFeaturestoreExcludeFeatureView(String name,
      Featurestore featurestore) {
    return em.createNamedQuery("TrainingDataset.findByFeaturestoreAndNameExcludeFeatureView", TrainingDataset.class)
        .setParameter("featurestore", featurestore)
        .setParameter("name", name)
        .getResultList();
  }
  
  /**
   * Retrieves a list of trainingDataset (different versions) given their name and feature store from the database
   * ordered by their version number in descending order
   *
   * @param name name of the trainingDataset
   * @param featurestore featurestore of the trainingDataset
   * @return a single TrainingDataset entity
   */
  public List<TrainingDataset> findByNameAndFeaturestoreOrderedDescVersion(String name, Featurestore featurestore) {
    return em.createNamedQuery("TrainingDataset.findByFeaturestoreAndNameOrderedByDescVersion", TrainingDataset.class)
      .setParameter("featurestore", featurestore)
      .setParameter("name", name)
      .getResultList();
  }

  /**
   * Retrieves a training dataset given its name, version and feature store from the database
   *
   * @param name name of the trainingDataset
   * @param version version of the trainingDataset
   * @param featurestore featurestore of the trainingDataset
   * @return a single TrainingDataset entity
   */
  public Optional<TrainingDataset> findByNameVersionAndFeaturestore(String name, Integer version,
                                                                    Featurestore featurestore) {
    try {
      return Optional.of(
          em.createNamedQuery("TrainingDataset.findByFeaturestoreAndNameVersion", TrainingDataset.class)
          .setParameter("featurestore", featurestore)
          .setParameter("name", name)
          .setParameter("version", version)
          .getSingleResult());
    } catch (NoResultException e) {
      return Optional.empty();
    }
  }

  /**
   * Retrieves all trainingDatasets from the database
   *
   * @return list of trainingDataset entities
   */
  @Override
  public List<TrainingDataset> findAll() {
    TypedQuery<TrainingDataset> query = em.createNamedQuery("TrainingDataset.findAll", TrainingDataset.class);
    return query.getResultList();
  }

  /**
   * Retrieves all trainingDatasets for a particular featurestore
   *
   * @param featurestore
   * @return
   */
  public List<TrainingDataset> findByFeaturestore(Featurestore featurestore) {
    TypedQuery<TrainingDataset> query = em.createNamedQuery("TrainingDataset.findByFeaturestore", TrainingDataset.class)
        .setParameter("featurestore", featurestore);
    return query.getResultList();
  }

  public Long countByFeaturestore(Featurestore featurestore) {
    return em.createNamedQuery("TrainingDataset.countByFeaturestore", Long.class)
        .setParameter("featurestore", featurestore)
        .getSingleResult();
  }

  public TrainingDataset findByFeatureViewAndVersion(FeatureView featureView, Integer version)
      throws FeaturestoreException {
    TypedQuery<TrainingDataset> query =
        em.createNamedQuery("TrainingDataset.findByFeatureViewAndVersion", TrainingDataset.class)
            .setParameter("featureView", featureView)
            .setParameter("version", version);
    return query.getResultList().stream()
        .findFirst()
        .orElseThrow(() -> new FeaturestoreException(RESTCodes.FeaturestoreErrorCode.TRAINING_DATASET_NOT_FOUND,
            Level.FINE, String.format("FeatureView name: %s, version: %s, td version: %s", featureView.getName(),
            featureView.getVersion(), version)));
  }

  public Optional<TrainingDataset> findByFeatureViewAndVersionNullable(FeatureView featureView, Integer version) {
    TypedQuery<TrainingDataset> query =
        em.createNamedQuery("TrainingDataset.findByFeatureViewAndVersion", TrainingDataset.class)
            .setParameter("featureView", featureView)
            .setParameter("version", version);
    return query.getResultList()
        .stream()
        .findFirst();
  }

  /**
   * Retrieves a list of trainingDataset (different versions) given a featureView from the database
   * ordered by their version number in descending order
   *
   * @param featureView
   * @return list of trainingDataset
   */
  public List<TrainingDataset> findByFeatureViewAndVersionOrderedDescVersion(FeatureView featureView) {
    return em.createNamedQuery("TrainingDataset.findByFeatureViewOrderedByDescVersion", TrainingDataset.class)
        .setParameter("featureView", featureView)
        .getResultList();
  }

  public List<TrainingDataset> findByFeatureView(FeatureView featureView) {
    TypedQuery<TrainingDataset> query = em.createNamedQuery("TrainingDataset.findByFeatureView", TrainingDataset.class)
        .setParameter("featureView", featureView);
    return query.getResultList();
  }
  
  public List<TrainingDataset> findByFeatureViews(List<FeatureView> featureViews) {
    if (featureViews.size() > settings.getSQLMaxSelectIn()) {
      List<TrainingDataset> result = new ArrayList<>();
      for(List<FeatureView> partition : Lists.partition(featureViews, settings.getSQLMaxSelectIn())) {
        TypedQuery<TrainingDataset> query =
          em.createNamedQuery("TrainingDataset.findByFeatureViewsOrderedByDescVersion", TrainingDataset.class);
        query.setParameter("featureViews", partition);
        result.addAll(query.getResultList());
      }
      return result;
    } else {
      TypedQuery<TrainingDataset> query =
        em.createNamedQuery("TrainingDataset.findByFeatureViewsOrderedByDescVersion", TrainingDataset.class);
      query.setParameter("featureViews", featureViews);
      return query.getResultList();
    }
  }

  /**
   * Gets the entity manager of the facade
   *
   * @return entity manager
   */
  @Override
  protected EntityManager getEntityManager() {
    return em;
  }

  public void removeTrainingDataset(TrainingDataset trainingDataset) {
    switch (trainingDataset.getTrainingDatasetType()) {
      case HOPSFS_TRAINING_DATASET:
        hopsfsTrainingDatasetFacade.remove(trainingDataset.getHopsfsTrainingDataset());
        break;
      case EXTERNAL_TRAINING_DATASET:
        externalTrainingDatasetFacade.remove(trainingDataset.getExternalTrainingDataset());
        break;
    }

    remove(trainingDataset);
  }
}
