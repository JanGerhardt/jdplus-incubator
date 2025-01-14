/*
 * Copyright 2022 National Bank of Belgium
 * 
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved 
 * by the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 * https://joinup.ec.europa.eu/software/page/eupl
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and 
 * limitations under the Licence.
 */
package jdplus.highfreq.base.r;

import jdplus.toolkit.base.api.data.DoubleSeq;
import tck.demetra.data.MatrixSerializer;
import tck.demetra.data.WeeklyData;
import jdplus.toolkit.base.api.math.functions.Optimizer;
import jdplus.toolkit.base.api.math.matrices.Matrix;
import jdplus.toolkit.base.api.ssf.SsfInitialization;
import jdplus.toolkit.base.api.timeseries.TsDomain;
import jdplus.toolkit.base.api.timeseries.TsPeriod;
import jdplus.toolkit.base.api.timeseries.calendars.EasterRelatedDay;
import jdplus.toolkit.base.api.timeseries.calendars.FixedDay;
import jdplus.toolkit.base.api.timeseries.calendars.Holiday;
import jdplus.toolkit.base.api.timeseries.calendars.HolidaysOption;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jdplus.highfreq.base.core.extendedairline.ExtendedAirlineMapping;
import jdplus.toolkit.base.core.math.matrices.FastMatrix;
import jdplus.sts.base.core.msts.AtomicModels;
import jdplus.sts.base.core.msts.CompositeModel;
import jdplus.sts.base.core.msts.CompositeModelEstimation;
import jdplus.sts.base.core.msts.ModelEquation;
import jdplus.sts.base.core.msts.StateItem;
import jdplus.toolkit.base.core.ssf.ISsfLoading;
import jdplus.toolkit.base.core.ssf.StateStorage;
import jdplus.toolkit.base.core.timeseries.calendars.HolidaysUtility;
import tck.demetra.data.Data;

/**
 *
 * @author palatej
 */
public class SplinesTest {

    final static DoubleSeq SERIES;

    static {
        DoubleSeq y;
        try {
            InputStream stream = Data.class.getResourceAsStream("/births.txt");
            Matrix edf = MatrixSerializer.read(stream);
            y = edf.column(0);
        } catch (IOException ex) {
            y = null;
        }
        SERIES = y;
    }

    private static void addDefault(List<Holiday> holidays) {
        holidays.add(FixedDay.NEWYEAR);
        holidays.add(FixedDay.MAYDAY);
        holidays.add(FixedDay.ASSUMPTION);
        holidays.add(FixedDay.ALLSAINTSDAY);
        holidays.add(FixedDay.CHRISTMAS);
        holidays.add(EasterRelatedDay.EASTERMONDAY);
        holidays.add(EasterRelatedDay.ASCENSION);
        holidays.add(EasterRelatedDay.WHITMONDAY);
    }

    public static Holiday[] france() {
        List<Holiday> holidays = new ArrayList<>();
        addDefault(holidays);
        holidays.add(new FixedDay(5, 8));
        holidays.add(new FixedDay(7, 14));
        holidays.add(FixedDay.ARMISTICE);
        return holidays.stream().toArray(i -> new Holiday[i]);
    }

    public static void main(String[] args) {
        DoubleSeq y = SERIES.log();

        TsPeriod start = TsPeriod.daily(1968, 1, 1);
        FastMatrix X = HolidaysUtility.regressionVariables(france(), TsDomain.of(start, y.length()), HolidaysOption.Skip, new int[]{6, 7}, false);

        long t0 = System.currentTimeMillis();

        int[] pos = new int[40];
        for (int i=0; i<pos.length; ++i){
            pos[i]=9*i;
        }

        CompositeModel model = new CompositeModel();
        //StateItem l = AtomicModels.localLevel("l", .01, false, Double.NaN);
        StateItem l = AtomicModels.localLinearTrend("l", .01, .01, false, false);
        StateItem sw = AtomicModels.seasonalComponent("sw", "HarrisonStevens", 7, .01, false);
        StateItem sy = AtomicModels.dailySplines("sy", 1968, pos, 0, .01, false);
        StateItem reg = AtomicModels.timeVaryingRegression("reg", X, 0.01, false);
        StateItem n = AtomicModels.noise("n", .01, false);
        ModelEquation eq = new ModelEquation("eq1", 0, true);
        eq.add(l);
        eq.add(sw);
        eq.add(sy);
        eq.add(n);
        eq.add(reg);
        model.add(l);
        model.add(sw);
        model.add(sy);
        model.add(n);
        model.add(reg);
        int len = y.length();
        FastMatrix M = FastMatrix.make(len, 1);
        model.add(eq);
        M.column(0).copy(y);
        CompositeModelEstimation mrslt = model.estimate(M, false, true, SsfInitialization.Augmented_Robust, Optimizer.LevenbergMarquardt, 1e-5, null);
        long t1 = System.currentTimeMillis();
        System.out.println(t1 - t0);
        StateStorage smoothedStates = mrslt.getSmoothedStates();
        ISsfLoading loading = sy.defaultLoading(0);
        int[] cmpPos = mrslt.getCmpPos();
        int[] cmpDim = mrslt.getSsf().componentsDimension();
        System.out.println(smoothedStates.getComponent(cmpPos[0]));
        System.out.println(smoothedStates.getComponent(cmpPos[1]));
        System.out.println(smoothedStates.getComponent(cmpPos[3]));
        for (int i = 0; i < smoothedStates.size(); ++i) {
            double z = loading.ZX(i, smoothedStates.a(i).extract(cmpPos[2], cmpDim[2]));
            System.out.print(z);
            System.out.print('\t');
        }
        System.out.println();
        for (int i = 0; i < smoothedStates.size(); ++i) {
            double z = X.row(i).dot(smoothedStates.a(i).extract(cmpPos[4], cmpDim[4]));
            System.out.print(z);
            System.out.print('\t');
        }
        System.out.println("");
        System.out.println(mrslt.getLikelihood().logLikelihood());
        System.out.println(DoubleSeq.of(mrslt.getFullParameters()));
        Arrays.stream(mrslt.getParametersName()).forEach(s -> System.out.println(s));
    }

    public static void main2(String[] args) {
        DoubleSeq y;
        try {
            InputStream stream = Data.class.getResourceAsStream("/usclaims.txt");
            Matrix us = MatrixSerializer.read(stream);
            y = us.column(0);
        } catch (IOException ex) {
            y = null;
        }

        TsPeriod start = TsPeriod.weekly(1967, 1, 7);
        y = DoubleSeq.of(WeeklyData.US_CLAIMS2).log();

        long t0 = System.currentTimeMillis();
        System.out.println(y);

        int[] pos = new int[]{1,15, 30, 89, 119, 125, 135, 140, 171, 202, 293, 355, 364};

        CompositeModel model = new CompositeModel();
//        StateItem l = AtomicModels.localLevel("l", .01, false, Double.NaN);
        StateItem l = AtomicModels.localLinearTrend("l", .01, .01, false, false);
//        StateItem sw = AtomicModels.seasonalComponent("sw", "HarrisonStevens", 7, .01, false);
        StateItem sy = AtomicModels.regularSplines("sy", 1967, new double[]{1,5,10,15,20,30,40,50}, 0, .01, false);
//        StateItem reg=AtomicModels.timeVaryingRegression("reg", X, 0.01, false);
        StateItem n = AtomicModels.noise("n", .01, false);
        ModelEquation eq = new ModelEquation("eq1", 0, true);
        eq.add(l);
        eq.add(sy);
        eq.add(n);
        model.add(l);
        model.add(sy);
        model.add(n);
        int len = y.length();
        FastMatrix M = FastMatrix.make(len, 1);
        model.add(eq);
        M.column(0).copy(y);
        CompositeModelEstimation mrslt = model.estimate(M, false, true, SsfInitialization.Augmented_Robust, Optimizer.LevenbergMarquardt, 1e-5, null);
        long t1 = System.currentTimeMillis();
        System.out.println(t1 - t0);
        System.out.println(mrslt.getLikelihood().logLikelihood());
        System.out.println(DoubleSeq.of(mrslt.getFullParameters()));
        Arrays.stream(mrslt.getParametersName()).forEach(s -> System.out.println(s));
        StateStorage smoothedStates = mrslt.getSmoothedStates();
        ISsfLoading loading = sy.defaultLoading(0);
        int[] cmpPos = mrslt.getCmpPos();
        int[] cmpDim = mrslt.getSsf().componentsDimension();
        System.out.println(smoothedStates.getComponent(cmpPos[0]));
        for (int i = 0; i < smoothedStates.size(); ++i) {
            double z = loading.ZX(i, smoothedStates.a(i).extract(cmpPos[1], cmpDim[1]));
            System.out.print(z);
            System.out.print('\t');
        }
        System.out.println();
        System.out.println(smoothedStates.getComponent(cmpPos[2]));
        System.out.println("");
    }
}
