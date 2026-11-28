package es.ulpgc.urbanmove.lambdas.relocation;

/**
 * De dónde sale la demanda. Es la única pieza que sabe que la demanda viene de analytics: si hubiera que leerla de
 * otro sitio (plan B del ADR-012, la capa raw/ del Data Lake), solo cambiaría la implementación.
 */
public interface DemandSource {

    /** Lanza UpstreamException si no se puede obtener. */
    Demand currentDemand(String correlationId);
}
