\name{h2o.example.wrapper}
\alias{h2o.glm.wrapper}
\alias{h2o.gbm.wrapper}
\alias{h2o.randomForest.wrapper}
\alias{h2o.deeplearning.wrapper}
\title{
Wrapper functions for h2o algorithms
}
\description{
This is an example h2o algorithm wrapper function.
}
\usage{
h2o.example.wrapper(x, y, data, key = "", family = "binomial", ...)
}

\arguments{
  \item{x}{
A vector containing the names of the predictors in the model.
}
  \item{y}{
The name of the response variable in the model.
}
  \item{data}{
An \code{\linkS4class{H2OParsedData}} object containing the variables in the model.
}
\item{key}{
  (Optional) The unique hex key assigned to the resulting model. If none is given, a key will automatically be generated.
}

\item{family}{
A description of the error distribution and link function to be used in the model.  This must be a character string.  Currently supports \code{"binomial"} and \code{"gaussian"}.  
}

}
\value{
An H2O model.
}

\author{
Erin LeDell \email{erin@h2o.ai}
}

\seealso{
\code{\link[h2oEnsemble::h2o.ensemble]{h2o.ensemble}}
}


\keyword{ utilities }
