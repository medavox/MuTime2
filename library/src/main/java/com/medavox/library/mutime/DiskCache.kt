package com.medavox.library.mutime

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log

internal const val SHARED_PREFS_KEY = "com.medavox.library.mutime.shared_preferences"
private const val KEY_SYSTEM_CLOCK_OFFSET = "system clock offset"
private const val KEY_UPTIME_OFFSET = "uptime offset"
private const val KEY_ROUND_TRIP_DELAY = "round trip delay"

private const val TAG = "Persistence"

/**Handles caching of offsets to disk using {@link SharedPreferences},
 *
 * This class keeps both an in-memory copy of the time data, plus
 * arbitrates access to the SharedPrefs copy, all under one API.
 * all the API user has to do is call {@link #getTimeData()}.
 * */
internal class DiskCache(private val sharedPrefs :SharedPreferences) : SntpClient.SntpResponseListener {
    
    /**Retrieve time offset information.
     * Gets in the in-memory copy if it exists,
     * or retrieves it from shared preferences,
     * or finally returns null.
     * @return a {@link TimeData} which can be used to compute the real time,
     * or null if no data is available.*/
    @Throws(MissingTimeDataException::class)
    fun getTimeData():TimeData {
        Log.w(TAG, "attempting to retrieve time data from SharedPreferences...")

        //Log.i(TAG, "is SharedPrefs null:"+(sharedPrefs == null));
        val roundTripDelay = sharedPrefs.getLong(KEY_ROUND_TRIP_DELAY, -1L)
        val upTime = sharedPrefs.getLong(KEY_UPTIME_OFFSET, -1L)
        val clockTime = sharedPrefs.getLong(KEY_SYSTEM_CLOCK_OFFSET, -1L)

        if (roundTripDelay == -1L || upTime == -1L || clockTime == -1L) {
            throw MissingTimeDataException("no time data in SharedPreferences. " +
                    "Has MuTime been run at least once?")
        }
        //copy the SharedPreferences data into the in-memory variables
        return TimeData(
                roundTripDelay=roundTripDelay,
                uptimeOffset=upTime,
                systemClockOffset=clockTime)
    }

    fun hasTimeData():Boolean {
        return sharedPrefs.contains(KEY_ROUND_TRIP_DELAY)
        && sharedPrefs.contains(KEY_UPTIME_OFFSET)
        && sharedPrefs.contains(KEY_SYSTEM_CLOCK_OFFSET)
        /*//clear the invalid data
          //correct data > slightly wrong data > no data > very wrong data
          timeData = null
          val editor: SharedPreferences.Editor? = sharedPrefs?.edit()
          if(editor != null) {
              editor.remove(KEY_ROUND_TRIP_DELAY)
              editor.remove(KEY_SYSTEM_CLOCK_OFFSET)
              editor.remove(KEY_UPTIME_OFFSET)
              editor.apply()
          }
        }*/
    }

    /**Saves the received {@link TimeData}, both locally as instance variables,
     * and into SharedPreferences.*/
    override fun onSntpTimeData(data:TimeData) {
        Log.d(TAG, "Saving true time info to disk: $data");

        val editor:SharedPreferences.Editor = sharedPrefs.edit()
        editor.putLong(KEY_ROUND_TRIP_DELAY, data.roundTripDelay)
        editor.putLong(KEY_SYSTEM_CLOCK_OFFSET, data.systemClockOffset)
        editor.putLong(KEY_UPTIME_OFFSET, data.uptimeOffset)
        editor.apply()
    }
}
