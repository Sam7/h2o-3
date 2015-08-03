package hex.grid;

import java.util.Map;

import hex.Model;
import hex.ModelBuilder;
import hex.ModelParametersBuilderFactory;
import hex.grid.HyperSpaceWalker.CartezianWalker;
import water.DKV;
import water.H2O;
import water.Job;
import water.Key;
import water.fvec.Frame;
import water.util.Log;
import water.util.PojoUtils;

/**
 * Grid search job.
 *
 * This job triggers sub-jobs for each point in hyper space. It produces <code>Grid</code> which
 * contains a list of build models.
 *
 * FIXME: should be driver which is passed to job as H2OCountedCompleter
 */
public final class GridSearch<MP extends Model.Parameters> extends Job<Grid> {

  /**
   * Produces a new model builder for given parameters.
   */
  private final transient ModelFactory<MP> _modelFactory;
  /**
   * Walks hyper space and for each point produces model parameters. It is used only locally to fire
   * new model builders via ModelFactory.
   */
  private final transient HyperSpaceWalker<MP> _hyperSpaceWalker;


  private GridSearch(Key gkey,
             ModelFactory<MP> modelFactory,
             HyperSpaceWalker<MP> hyperSpaceWalker) {
    super(gkey, modelFactory.getModelName() + " Grid Search");
    assert modelFactory != null : "Grid search needs to know how to build a new model!";
    assert hyperSpaceWalker != null : "Grid search needs to know to how walk around hyper space!";
    //_paramsBuilderFactory = paramsBuilderFactory;
    _modelFactory = modelFactory;
    _hyperSpaceWalker = hyperSpaceWalker;

    // Note: do not validate parameters of created model builders here
    // Leave it to launch time, and just mark the build failed
  }

  GridSearch start() {
    final int gridSize = _hyperSpaceWalker.getHyperSpaceSize();
    Log.info("Starting gridsearch: estimated size of search space =" + gridSize);
    // Create grid object and lock it
    // Creation is done here, since we would like make sure that after leaving
    // this function the grid object is in DKV and accessible.
    Grid<MP> grid = DKV.getGet(dest());
    if (grid != null) {
      grid.write_lock(jobKey());
    } else {
      grid = new Grid<MP>(dest(), _hyperSpaceWalker.getParams(), _hyperSpaceWalker.getHyperParamNames(), _modelFactory.getModelName());
      grid.delete_and_lock(jobKey());
    }
    // Java trick
    final Grid<MP> gridToExpose = grid;
    // Install this as job functions
    start(new H2O.H2OCountedCompleter() {
      @Override
      public void compute2() {
        gridSearch(gridToExpose);
        tryComplete();
      }
    }, gridSize, true);
    return this;
  }

  /** Returns expected number of models in resulting Grid object.
   *
   * The number can differ from final number of models due to visiting
   * duplicate points in hyper space.
   *
   * @return expected number of models produced by this grid search
   */
  public int getModelCount() {
    return _hyperSpaceWalker.getHyperSpaceSize();
  }

  /**
   * @return the set of models covered by this grid search, some may be null if the search is in
   * progress or otherwise incomplete. It can also contain duplicate entries if input grid search
   * specification includes them.
   *
   * FIXME: cannot iterate over space again since it is already used below in model building
   */
  /*
  public Model[] models() {
    MP paramsPrototype = _params;
    Model[] ms = new Model[_totalModels];
    int mcnt = 0;
    Object[] hypers = new Object[_hyperParamNames.length];
    for (int[] hidx = new int[_hyperParamNames.length]; hidx != null; hidx = nextModel(hidx)) {
      MP params = getModelParams((MP) paramsPrototype.clone(), hypers(hidx, hypers));
      ms[mcnt++] = model(params).get();
    }
    return ms;
  } */

  // Classic grid search over hyper-parameter space
  private void gridSearch(Grid<MP> grid) {
    // FIXME: grid should be locked and unlocked at the end
    MP params = null;
    Model model = null;
    try {
      while ((params = _hyperSpaceWalker.nextModelParameters(model)) != null) {
        System.err.println(params.toJsonString());
        if (!isRunning()) {
          cancel();
          return;
        }
        // Sequential model building
        model = buildModel(params, grid);
      }
      // Grid search is done
      done();
    } finally {
      // Unlock grid object
      grid.unlock(jobKey());
    }
  }

  /**
   * @param hypers A set of hyper parameter values
   * @return A model run with these parameters, typically built on demand and cached - expected to
   * be an expensive operation.  If the model in question is "in progress", a 2nd build will NOT be
   * kicked off. This is a blocking call.
   */
  private Model buildModel(final MP params, Grid<MP> grid) {
    // Make sure that the model is not yet built (can be case of duplicated hyper parameters)
    Key<Model> key = grid.getModelKey(params);
    // It was already built
    if (key != null) {
      return key.get();
    }
    // FIXME: get checksum here since model builder will modify instance of params!!!
    long checksum = params.checksum();
    // Build a new model
    // THIS IS BLOCKING call since we do not have enough information about free resources
    // FIXME: we should allow here any launching strategy
    Model m = null;
    try {
      m = (Model) (startBuildModel(params, grid).get());
      grid.putModel(checksum, m._key);
      grid.update(jobKey());
    } catch (RuntimeException e) {
      Log.warn("Grid search: model builder for parameters " + params + " failed! Exception: ", e);
    }
    return m;
  }

  /**
   * @param hypers A set of hyper parameter values
   * @return A Future of a model run with these parameters, typically built on demand and not cached
   * - expected to be an expensive operation.  If the model in question is "in progress", a 2nd
   * build will NOT be kicked off. This is a non-blocking call.
   */
  private ModelBuilder startBuildModel(MP params, Grid<MP> grid) {
    if (grid.getModel(params) != null) {
      return null;
    }
    ModelBuilder mb = _modelFactory.buildModel(params);
    mb.trainModel();
    return mb;
  }

  /**
   * @return Factory method to return the grid key for a particular modeling class and frame.
   */
  protected static Key<Grid> gridKeyName(String modelName, Frame fr) {
    if (fr._key == null) {
      throw new IllegalArgumentException("The frame being grid-searched over must have a Key");
    }
    return Key.make("Grid_" + modelName + "_" + fr._key.toString());
  }


  /**
   * @param params      Default parameters for grid search builder
   * @param hyperParams A set of arrays of hyper parameter values, used to specify a simple
   *                    fully-filled-in grid search.
   * @return GridSearch Job, with models run with these parameters, built as needed - expected to be
   * an expensive operation.  If the models in question are "in progress", a 2nd build will NOT be
   * kicked off.  This is a non-blocking call.
   */
  public static <MP extends Model.Parameters> GridSearch startGridSearch(
      final MP params,
      final Map<String, Object[]> hyperParams,
      final ModelFactory<MP> modelFactory,
      final ModelParametersBuilderFactory<MP> paramsBuilderFactory) {
    // Create a walker to traverse hyper space of model parameters
    CartezianWalker<MP> hyperSpaceWalker = new CartezianWalker<>(params, hyperParams, paramsBuilderFactory);

    return startGridSearch(modelFactory, hyperSpaceWalker);
  }


  public static <MP extends Model.Parameters> GridSearch startGridSearch(final MP params,
                                                                         final Map<String, Object[]> hyperParams,
                                                                         final ModelFactory<MP> modelFactory) {
    return startGridSearch(params, hyperParams, modelFactory, new SimpleParametersBuilderFactory<MP>());
  }

  public static <MP extends Model.Parameters> GridSearch startGridSearch(
      final ModelFactory<MP> modelFactory,
      final HyperSpaceWalker<MP> hyperSpaceWalker) {
    // Compute key for destination object representing grid
    Key<Grid> gridKey = gridKeyName(modelFactory.getModelName(), hyperSpaceWalker.getParams().train());
    // Start the search
    return new GridSearch(gridKey, modelFactory, hyperSpaceWalker).start();
  }

  /**
   * FIXME append missing info
   * @param <MP>
   */
  static class SimpleParametersBuilderFactory<MP extends Model.Parameters>
      implements ModelParametersBuilderFactory<MP> {

    @Override
    public ModelParametersBuilder<MP> get(MP initialParams) {
      return new SimpleParamsBuilder<>(initialParams);
    }

    public static class SimpleParamsBuilder<MP extends Model.Parameters>
        implements ModelParametersBuilder<MP> {

      final private MP params;

      public SimpleParamsBuilder(MP initialParams) {
        params = initialParams;
      }

      @Override
      public ModelParametersBuilder<MP> set(String name, Object value) {
        PojoUtils.setField(params, name, value, PojoUtils.FieldNaming.CONSISTENT);
        return this;
      }

      @Override
      public MP build() {
        return params;
      }
    }
  }
}
