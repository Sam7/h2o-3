package hex.api;

import hex.grid.Grid;
import hex.grid.ModelFactories;
import hex.grid.ModelFactory;
import hex.schemas.GBMGridSearchV99;
import hex.schemas.GBMV3;
import hex.tree.gbm.GBMModel;

/**
 * A specific handler for GBM grid search.
 */
public class GBMGridSearchHandler extends GridSearchHandler<Grid<GBMModel.GBMParameters>,
    GBMGridSearchV99,
    GBMModel.GBMParameters,
    GBMV3.GBMParametersV3> {

  /* This is kind of trick, since our REST framework was not able to
     recognize overloaded function do train. Hence, we use delegation here.
   */
  public GBMGridSearchV99 train(int version, GBMGridSearchV99 gridSearchSchema) {
    return super.do_train(version, gridSearchSchema);
  }

  @Override
  protected ModelFactory<GBMModel.GBMParameters> getModelFactory() {
    return ModelFactories.GBM_MODEL_FACTORY;
  }
}
