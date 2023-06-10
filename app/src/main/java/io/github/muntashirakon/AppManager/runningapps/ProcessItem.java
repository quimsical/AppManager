// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.runningapps;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.core.os.ParcelCompat;

import java.util.Objects;

import io.github.muntashirakon.AppManager.ipc.ps.ProcessEntry;
import io.github.muntashirakon.AppManager.users.Owners;

public class ProcessItem implements Parcelable {
    public final int pid;
    public final int ppid;
    public final long rss;
    public final int uid;
    public final String user;
    public final String context;

    public String state;
    public String state_extra;
    public String name;

    @NonNull
    private final ProcessEntry processEntry;

    public ProcessItem(@NonNull ProcessEntry processEntry) {
        this.processEntry = processEntry;
        pid = processEntry.pid;
        ppid = processEntry.ppid;
        rss = processEntry.residentSetSize;
        uid = processEntry.users.fsUid;
        context = processEntry.seLinuxPolicy;
        user = Owners.getOwnerName(processEntry.users.fsUid);
    }

    /**
     * @see <a href="https://stackoverflow.com/a/16736599">How do I get the total CPU usage of an application from /proc/pid/stat?</a>
     */
    public double getCpuTimeInPercent() {
        return processEntry.cpuTimeConsumed * 100. / processEntry.elapsedTime;
    }

    public long getCpuTimeInMillis() {
        return processEntry.cpuTimeConsumed * 1000;
    }

    public String getCommandlineArgsAsString() {
        return processEntry.name.replace('\u0000', ' ');
    }

    public String[] getCommandlineArgs() {
        return processEntry.name.split("\u0000");
    }

    public long getMemory() {
        return processEntry.residentSetSize << 12;
    }

    public long getVirtualMemory() {
        return processEntry.virtualMemorySize;
    }

    public long getSharedMemory() {
        return processEntry.sharedMemory << 12;
    }

    public int getPriority() {
        return processEntry.priority;
    }

    public int getThreadCount() {
        return processEntry.threadCount;
    }

    protected ProcessItem(@NonNull Parcel in) {
        processEntry = Objects.requireNonNull(ParcelCompat.readParcelable(in, ProcessEntry.class.getClassLoader(), ProcessEntry.class));
        pid = processEntry.pid;
        ppid = processEntry.ppid;
        rss = processEntry.residentSetSize;
        uid = processEntry.users.fsUid;
        context = processEntry.seLinuxPolicy;

        user = in.readString();
        state = in.readString();
        state_extra = in.readString();
        name = in.readString();
    }

    public static final Creator<ProcessItem> CREATOR = new Creator<ProcessItem>() {
        @NonNull
        @Override
        public ProcessItem createFromParcel(Parcel in) {
            return new ProcessItem(in);
        }

        @NonNull
        @Override
        public ProcessItem[] newArray(int size) {
            return new ProcessItem[size];
        }
    };

    @Override
    @NonNull
    public String toString() {
        return "ProcessItem{" +
                "pid=" + pid +
                ", ppid=" + ppid +
                ", rss=" + rss +
                ", user='" + user + '\'' +
                ", uid=" + uid +
                ", state='" + state + '\'' +
                ", state_extra='" + state_extra + '\'' +
                ", name='" + name + '\'' +
                ", context='" + context + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessItem)) return false;
        ProcessItem that = (ProcessItem) o;
        return pid == that.pid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pid);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(processEntry, flags);
        dest.writeString(user);
        dest.writeString(state);
        dest.writeString(state_extra);
        dest.writeString(name);
    }
}
