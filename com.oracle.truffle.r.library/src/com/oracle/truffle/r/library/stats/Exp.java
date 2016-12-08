/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (C) 1998 Ross Ihaka
 * Copyright (c) 2000, The R Core Team
 * Copyright (c) 2016, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.library.stats;

import com.oracle.truffle.r.library.stats.DPQ.EarlyReturn;
import com.oracle.truffle.r.library.stats.RandGenerationFunctions.RandFunction1_Double;
import com.oracle.truffle.r.library.stats.RandGenerationFunctions.RandomNumberProvider;
import com.oracle.truffle.r.library.stats.StatsFunctions.Function2_1;
import com.oracle.truffle.r.library.stats.StatsFunctions.Function2_2;

public class Exp {
    public static final class DExp implements Function2_1 {
        @Override
        public double evaluate(double x, double scale, boolean giveLog) {
            /* NaNs propagated correctly */
            if (Double.isNaN(x) || Double.isNaN(scale)) {
                return x + scale;
            }

            if (scale <= 0.0) {
                return StatsUtil.mlError();
            }

            if (x < 0.) {
                return DPQ.d0(giveLog);
            }
            return (giveLog ? (-x / scale) - Math.log(scale) : Math.exp(-x / scale) / scale);
        }
    }

    public static final class RExp implements RandFunction1_Double {
        @Override
        public double evaluate(double scale, RandomNumberProvider rand) {
            if (!Double.isFinite(scale) || scale <= 0.0) {
                return scale == 0. ? 0. : StatsUtil.mlError();
            }
            return scale * rand.expRand();
        }
    }

    public static final class PExp implements Function2_2 {
        @Override
        public double evaluate(double x, double scale, boolean lowerTail, boolean logP) {
            if (Double.isNaN(x) || Double.isNaN(scale)) {
                return x + scale;
            }
            if (scale < 0) {
                return StatsUtil.mlError();
            }

            if (x <= 0.) {
                return DPQ.dt0(logP, lowerTail);
            }

            /* same as weibull( shape = 1): */
            x = -(x / scale);
            return lowerTail ? (logP ? DPQ.log1Exp(x, logP) : -StatsUtil.expm1(x)) : DPQ.dExp(x, logP);
        }
    }

    public static final class QExp implements Function2_2 {
        @Override
        public double evaluate(double p, double scale, boolean lowerTail, boolean logP) {
            if (Double.isNaN(p) || Double.isNaN(scale)) {
                return p + scale;
            }

            if (scale < 0) {
                return StatsUtil.mlError();
            }

            try {
                DPQ.qQP01Check(p, logP);
            } catch (EarlyReturn e) {
                return e.result;
            }

            if (p == DPQ.dt0(logP, lowerTail)) {
                return 0;
            }

            return -scale * DPQ.dtCLog(p, lowerTail, logP);

        }
    }
}
