package com.medavox.library.mutime;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;


public class Ntp {

    private static final String TAG = Ntp.class.getSimpleName();
    private static final SntpClient client = new SntpClient();

    private static int _repeatCount = 5;

    private int _retryCount = 20;

    private static class StringToTimeDataThread extends Thread {
        private String ntpHost;
        private SntpClient.SntpResponseListener listener;

        StringToTimeDataThread(String ntpHost, SntpClient.SntpResponseListener listener) {
            this.ntpHost = ntpHost;
            this.listener = listener;
        }

        @Override
        public void run() {
            TimeData bestResponse = bestResponseAgainstSingleIp(_repeatCount, ntpHost);
            listener.onSntpTimeData(bestResponse);
        }
    }

    /**
     * Initialize MuTime
     * Use this if you want to resolve the NTP Pool address to individual IPs yourself
     * <p>
     * See https://github.com/instacart/truetime-android/issues/42
     * to understand why you may want to do something like this.
     *
     * @param resolvedNtpAddresses list of resolved IP addresses for an NTP request
     * @return Observable of detailed long[] containing most important parts of the actual NTP response
     * See RESPONSE_INDEX_ prefixes in {@link SntpClient} for details
     */
    public static void performNtpAlgorithm(InetAddress... addresses) {
        for (InetAddress address : addresses) {
            String ntpHost = address.getHostAddress();
            StringToTimeDataThread doer = new StringToTimeDataThread(ntpHost, dynamicCollater);
            doer.start();
        }
    }

    private static SntpClient.SntpResponseListener dynamicCollater = new SntpClient.SntpResponseListener() {
        private Set<TimeData> timeDatas = new ConcurrentSkipListSet(clockOffsetSorter);

        /**Each time we receive new data, recalculate the median offset
         * and send the results to persistence*/
        @Override
        public void onSntpTimeData(TimeData data) {
            if (data != null) {
                timeDatas.add(data);
                TimeData[] asArray = new TimeData[timeDatas.size()];
                TimeData newMedian = filterMedianResponse(timeDatas.toArray(asArray));
                MuTime.persistence.onSntpTimeData(newMedian);
            }
        }
    };

    public static InetAddress[] resolveMultipleNtpHosts(final String... ntpPoolAddresses) {
        final InetAddress[][] allResults = new InetAddress[ntpPoolAddresses.length][];
        ParallelProcess<String, InetAddress[]> wnr = new ParallelProcess<>(ntpPoolAddresses, allResults);
        wnr.doWork(new ParallelProcess.Worker<String, InetAddress[]>() {
            @Override public void performProcess(String input, InetAddress[] output) {
                output = resolveMultipleNtpHosts(input);
            }
        });
        wnr.waitTillFinished();
        Set<InetAddress> asSet = new HashSet<>();
        for(InetAddress[] array : allResults) {
            if(array != null) {
                for(InetAddress i : array) {
                    if(i != null) {
                        asSet.add(i);
                    }
                }
            }
        }
        InetAddress[] ret = new InetAddress[asSet.size()];
        return asSet.toArray(ret);
    }

    /**Initialize MuTime
     * A single NTP pool server is provided.
     * Using DNS we resolve that to multiple IP hosts
     * (See {@link #initializeNtp(InetAddress...)} for manually resolved IPs)
     *
     * Use this instead of {@link #initializeRx(String)} if you wish to also get additional info for
     * instrumentation/tracking actual NTP response data
     *
     * @param ntpPoolAddress NTP pool server e.g. time.apple.com, 0.us.pool.ntp.org
     * @return an array of reachable IP addresses which map to the given ntp pool address
     */
    public static InetAddress[] resolveNtpPoolToIpAddresses(String ntpPoolAddress) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(ntpPoolAddress);
            if (addresses != null & addresses.length > 0) {
                //remove unreachable addresses
                Set<InetAddress> ips = new HashSet<InetAddress>(addresses.length);
                for (InetAddress a : addresses) {
                    if (isReachable(a)) {
                        ips.add(a);
                    }
                }
                InetAddress[] ret = new InetAddress[ips.size()];
                return ips.toArray(ret);
            } else {
                return addresses;
            }
        } catch (UnknownHostException uhe) {
            Log.e(TAG, "failed to resolve ntp pool \"" + ntpPoolAddress + "\":" + uhe);
        }
        return null;
    }

    /**
     * Takes a single NTP host (as a String),
     * performs an SNTP request on it repeatCount number of times,
     * and returns the single result with the lowest round-trip delay
     */
    private static TimeData bestResponseAgainstSingleIp(final int repeatCount, String ntpHost) {
        TimeData[] responses = new TimeData[repeatCount];
        ParallelProcess<String, TimeData> para
                = new ParallelProcess<String, TimeData>(ntpHost, responses);
        para.doWork(new ParallelProcess.Worker<String, TimeData>() {
            @Override
            public void performProcess(String ntpHost, TimeData result) {
                try {
                    result = new SntpRequest(ntpHost, null).send();
                } catch (IOException ioe) {
                    Log.w(TAG, "request to \"" + ntpHost + "\" failed:" + ioe);
                }
            }
        });
        para.waitTillFinished();

        return filterLeastRoundTripDelay(responses);
    }

    /**
     * Takes a List of NTP responses, and returns the one with the smallest round-trip delay
     */
    private static TimeData filterLeastRoundTripDelay(TimeData... responseTimeList) {
        long bestRoundTrip = Long.MAX_VALUE;
        int bestIndex = -1;
        for (int i = 0; i < responseTimeList.length; i++) {
            if (responseTimeList[i] != null &&
                    responseTimeList[i].getRoundTripDelay() < bestRoundTrip) {
                bestRoundTrip = responseTimeList[i].getRoundTripDelay();
                bestIndex = i;
            }
        }
        return responseTimeList[bestIndex];
    }

    private static Comparator<TimeData> clockOffsetSorter = new Comparator<TimeData>() {
        @Override
        public int compare(TimeData lhsParam, TimeData rhsParam) {
            long lhs = lhsParam.getClockOffset();
            long rhs = rhsParam.getClockOffset();
            return lhs < rhs ? -1 : (lhs == rhs ? 0 : 1);
        }
    };

    /**
     * Takes a list of NTP responses, and returns the one with the median value for clock offset
     */
    private static TimeData filterMedianResponse(TimeData... bestResponses) {
        Arrays.sort(bestResponses, clockOffsetSorter);
        return bestResponses[bestResponses.length / 2];
    }

    private static boolean isReachable(InetAddress addr) {
        try {
            Socket soc = new Socket();
            soc.connect(new InetSocketAddress(addr, 80), 5_000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}