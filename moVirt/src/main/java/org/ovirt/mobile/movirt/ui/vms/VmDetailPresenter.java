package org.ovirt.mobile.movirt.ui.vms;

import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;
import org.ovirt.mobile.movirt.auth.account.AccountDeletedException;
import org.ovirt.mobile.movirt.auth.account.EnvironmentStore;
import org.ovirt.mobile.movirt.auth.account.data.ClusterAndEntity;
import org.ovirt.mobile.movirt.auth.account.data.Selection;
import org.ovirt.mobile.movirt.facade.ConsoleFacade;
import org.ovirt.mobile.movirt.facade.VmFacade;
import org.ovirt.mobile.movirt.model.Cluster;
import org.ovirt.mobile.movirt.model.Console;
import org.ovirt.mobile.movirt.model.Snapshot;
import org.ovirt.mobile.movirt.model.Vm;
import org.ovirt.mobile.movirt.model.enums.ConsoleProtocol;
import org.ovirt.mobile.movirt.model.enums.SnapshotStatus;
import org.ovirt.mobile.movirt.provider.ProviderFacade;
import org.ovirt.mobile.movirt.rest.ConnectivityHelper;
import org.ovirt.mobile.movirt.rest.SimpleResponse;
import org.ovirt.mobile.movirt.rest.dto.ConsoleConnectionDetails;
import org.ovirt.mobile.movirt.ui.Constants;
import org.ovirt.mobile.movirt.ui.ProgressBarResponse;
import org.ovirt.mobile.movirt.ui.mvp.AccountDisposablesProgressBarPresenter;
import org.ovirt.mobile.movirt.util.ConsoleHelper;
import org.ovirt.mobile.movirt.util.ObjectUtils;
import org.ovirt.mobile.movirt.util.message.CommonMessageHelper;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

@EBean
public class VmDetailPresenter extends AccountDisposablesProgressBarPresenter<VmDetailPresenter, VmDetailContract.View>
        implements VmDetailContract.Presenter {

    private final Subject<Map<ConsoleProtocol, Console>> consoles = BehaviorSubject
            .createDefault(Collections.<ConsoleProtocol, Console>emptyMap()).toSerialized();

    private final Subject<MenuState> menuState = PublishSubject.<MenuState>create().toSerialized();

    private String vmId;
    private Vm vm;

    private Selection selection;

    @Bean
    ProviderFacade providerFacade;

    @RootContext
    Context context;

    @Bean
    EnvironmentStore environmentStore;

    @Bean
    CommonMessageHelper commonMessageHelper;

    @Bean
    ConnectivityHelper connectivityHelper;

    @Override
    public VmDetailPresenter setVmId(String vmId) {
        this.vmId = vmId;
        return this;
    }

    @Override
    public VmDetailPresenter initialize() {
        super.initialize();
        ObjectUtils.requireNotNull(vmId, "vmId");

        final Observable<Vm> vmObservable = providerFacade.query(Vm.class)
                .id(vmId)
                .singleAsObservable();

        getDisposables().add(vmObservable
                .switchMap(vm -> providerFacade.query(Cluster.class)
                        .where(Cluster.ID, vm.getClusterId())
                        .singleAsObservable()
                        .map(c -> new ClusterAndEntity<>(vm, c))
                        .startWith(new ClusterAndEntity<>(vm, null)))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(wrapper -> {
                    getView().displayTitle(wrapper.entity.getName());

                    vm = wrapper.entity;
                    selection = new Selection(account, new Selection.SelectedCluster(wrapper.cluster),
                            wrapper.getClusterName(), wrapper.entity.getName());

                    getView().displayStatus(selection);
                }));

        getDisposables().add(Observable.combineLatest(
                vmObservable,
                providerFacade.query(Snapshot.class).where(Snapshot.VM_ID, vmId).asObservable(),
                providerFacade.query(Console.class).where(Console.VM_ID, vmId).asObservable(), Wrapper::new)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(wrapper -> {
                    boolean menuCreateSnapshotVisibility = !Snapshot.containsOneOfStatuses(wrapper.snapshots, SnapshotStatus.LOCKED, SnapshotStatus.IN_PREVIEW);
                    Map<ConsoleProtocol, Console> allConsoles = new EnumMap<>(ConsoleProtocol.class);

                    for (Console console : wrapper.consoles) {
                        allConsoles.put(console.getProtocol(), console);
                    }

                    consoles.onNext(allConsoles);
                    menuState.onNext(new MenuState(wrapper.vm.getStatus(),
                            menuCreateSnapshotVisibility,
                            allConsoles.containsKey(ConsoleProtocol.SPICE),
                            allConsoles.containsKey(ConsoleProtocol.VNC)));
                }));

        getDisposables().add(menuState.distinctUntilChanged()
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> getView().displayMenu(state)));

        getDisposables().add(rxStore.isSyncInProgressObservable(account)
                .skip(1) // do not refresh current state
                .filter(syncStatus -> !syncStatus.isInProgress())
                .subscribeOn(Schedulers.computation())
                .subscribe(syncStatus -> syncConsoles()));

        if (connectivityHelper.isNetworkAvailable()) {
            syncConsoles();
            if (!environmentStore.isInSync(account)) {
                syncVm();
            }
        }

        return this;
    }

    @Override
    public void beginMigration() {
        getView().startMigrationActivity(selection, vm.getHostId(), vm.getClusterId());
    }

    @Background
    protected void syncVm() {
        VmFacade facade = environmentStore.getEnvironment(account).getFacade(Vm.class);
        facade.syncOne(new ProgressBarResponse<>(VmDetailPresenter.this), vmId);
    }

    @Background
    protected void syncConsoles() {
        try {
            ConsoleFacade facade = environmentStore.getEnvironment(account).getFacade(Console.class);
            facade.syncAll(new ProgressBarResponse<>(this), vmId);
        } catch (AccountDeletedException ignore) {
        }
    }

    @Override
    @Background
    public void createSnapshot(org.ovirt.mobile.movirt.rest.dto.Snapshot snapshot) {
        environmentStore.safeOvirtClientCall(account,
                client -> client.createSnapshot(snapshot, vmId, new SimpleResponse<Void>() {
                    @Override
                    public void onResponse(Void aVoid) throws RemoteException {
                        try { // refresh snapshots
                            environmentStore.getEnvironment(account).getFacade(Snapshot.class).syncAll(vmId);
                        } catch (AccountDeletedException ignore) {
                        }
                    }
                }));
    }

    @Override
    public void destroy() {
        super.destroy();
        consoles.onComplete();
        menuState.onComplete();
        try {
            environmentStore.getEventProviderHelper(account).deleteTemporaryEvents();
        } catch (AccountDeletedException ignore) {
        }
    }

    @Override
    public void editTriggers() {
        getView().startEditTriggersActivity(selection, vmId);
    }

    @Override
    @Background
    public void migrateToDefault() {
        environmentStore.safeOvirtClientCall(account,
                client -> client.migrateVmToDefaultHost(vmId, new SyncVmResponse()));
    }

    @Override
    @Background
    public void migrateTo(String hostId) {
        environmentStore.safeOvirtClientCall(account,
                client -> client.migrateVmToHost(vmId, hostId, new SyncVmResponse()));
    }

    @Override
    @Background
    public void startVm() {
        environmentStore.safeOvirtClientCall(account,
                client -> client.startVm(vmId, new SyncVmResponse()));
    }

    @Override
    @Background
    public void stopVm() {
        environmentStore.safeOvirtClientCall(account,
                client -> client.stopVm(vmId, new SyncVmResponse()));
    }

    @Override
    @Background
    public void rebootVm() {
        environmentStore.safeOvirtClientCall(account,
                client -> client.rebootVm(vmId, new SyncVmResponse()));
    }

    @Override
    @Background
    public void cancelMigration() {
        environmentStore.safeOvirtClientCall(account,
                client -> client.cancelMigration(vmId, new SyncVmResponse()));
    }

    @Override
    @Background
    public void openConsole(final ConsoleProtocol protocol) {
        Console console = consoles.blockingFirst().get(protocol);
        if (console != null) {
            try {
                environmentStore.getEnvironment(account).getVvClient()
                        .getConsoleConnectionDetails(vmId, console.getId(), new ProgressBarResponse<ConsoleConnectionDetails>(this) {
                            @Override
                            public void onResponse(ConsoleConnectionDetails details) throws RemoteException {
                                connectToConsole(details);
                            }
                        });
            } catch (AccountDeletedException ignore) {
            }
        }
    }

    private void connectToConsole(final ConsoleConnectionDetails details) {
        try {
            String caCertPath = null;
            if (details.getProtocol() == ConsoleProtocol.SPICE && details.getTlsPort() > 0) {
                caCertPath = Constants.getConsoleCertPath(context);

                ConsoleHelper.saveConsoleCertToFile(caCertPath, details.getCertificate());
            }
            final VmDetailContract.View view = getView();
            if (view != null) {
                view.startConsoleActivity(Uri.parse(ConsoleHelper.makeConsoleUrl(details, caCertPath)));
            }
        } catch (IllegalArgumentException e) {
            commonMessageHelper.showToast(e.getMessage());
        } catch (Exception e) {
            commonMessageHelper.showToast("Failed to open console client. Check if aSPICE/bVNC is installed.");
        }
    }

    private class SyncVmResponse extends SimpleResponse<Void> {
        @Override
        public void onResponse(Void aVoid) throws RemoteException {
            try {
                syncVm();
                syncConsoles();
            } catch (AccountDeletedException ignore) {
            }
        }
    }

    public static class Wrapper {
        final Vm vm;
        final List<Snapshot> snapshots;
        final List<Console> consoles;

        public Wrapper(Vm vm, List<Snapshot> snapshots, List<Console> consoles) {
            this.vm = vm;
            this.snapshots = snapshots;
            this.consoles = consoles;
        }
    }
}
