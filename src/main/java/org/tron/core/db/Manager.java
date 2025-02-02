package org.tron.core.db;

import static org.tron.core.config.Parameter.ChainConstant.SOLIDIFIED_THRESHOLD;
import static org.tron.core.config.Parameter.NodeConstant.MAX_TRANSACTION_PENDING;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.util.Pair;
import javax.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.DateTime;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.abi.FunctionReturnDecoder;
import org.tron.abi.TypeReference;
import org.tron.abi.datatypes.BytesType;
import org.tron.abi.datatypes.Event;
import org.tron.abi.datatypes.Type;
import org.tron.abi.datatypes.generated.AbiTypes;
import org.tron.abi.datatypes.generated.Bytes32;
import org.tron.common.overlay.discover.node.Node;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.tron.common.storage.DepositImpl;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ForkController;
import org.tron.common.utils.SessionOptional;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.ReceiptCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.TransactionInfoCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.Parameter.ChainConstant;
import org.tron.core.config.args.Args;
import org.tron.core.config.args.GenesisBlock;
import org.tron.core.db.KhaosDatabase.KhaosBlock;
import org.tron.core.db2.core.ISession;
import org.tron.core.db2.core.ITronChainBase;
import org.tron.core.exception.AccountResourceInsufficientException;
import org.tron.core.exception.BadBlockException;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.BadNumberBlockException;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractSizeNotEqualToOneException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.DupTransactionException;
import org.tron.core.exception.HeaderNotFound;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.NonCommonBlockException;
import org.tron.core.exception.ReceiptCheckErrException;
import org.tron.core.exception.TaposException;
import org.tron.core.exception.TooBigTransactionException;
import org.tron.core.exception.TooBigTransactionResultException;
import org.tron.core.exception.TransactionExpirationException;
import org.tron.core.exception.UnLinkedBlockException;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.exception.ValidateScheduleException;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.core.witness.ProposalController;
import org.tron.core.witness.WitnessController;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Block;


@Slf4j (topic = "Manager")
@Component
public class Manager {

  private Cache<byte[], Protocol.SmartContract.ABI> abiCache = CacheBuilder.newBuilder()
      .maximumSize(100_000).expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(100_000)
      .recordStats().build();

  // db store
  @Autowired
  private AccountStore accountStore;
  @Autowired
  private TransactionStore transactionStore;
  @Autowired
  private BlockStore blockStore;
  @Autowired
  private WitnessStore witnessStore;
  @Autowired
  private AssetIssueStore assetIssueStore;
  @Autowired
  private DynamicPropertiesStore dynamicPropertiesStore;
  @Autowired
  private BlockIndexStore blockIndexStore;
  @Autowired
  private AccountIdIndexStore accountIdIndexStore;
  @Autowired
  private AccountIndexStore accountIndexStore;
  @Autowired
  private WitnessScheduleStore witnessScheduleStore;
  @Autowired
  private RecentBlockStore recentBlockStore;
  @Autowired
  private VotesStore votesStore;
  @Autowired
  private ProposalStore proposalStore;
  @Autowired
  private ExchangeStore exchangeStore;
  @Autowired
  private TransactionHistoryStore transactionHistoryStore;
  @Autowired
  private CodeStore codeStore;
  @Autowired
  private ContractStore contractStore;
  @Autowired
  @Getter
  private StorageRowStore storageRowStore;

  // for network
  @Autowired
  private PeersStore peersStore;


  @Autowired
  private KhaosDatabase khaosDb;


  private BlockCapsule genesisBlock;
  @Getter
  @Autowired
  private RevokingDatabase revokingStore;

  @Getter
  private SessionOptional session = SessionOptional.instance();

  @Getter
  @Setter
  private boolean isSyncMode;

  @Getter
  @Setter
  private String netType;

  @Getter
  @Setter
  private WitnessController witnessController;

  @Getter
  @Setter
  private ProposalController proposalController;

  private ExecutorService validateSignService;

  private Thread repushThread;

  private boolean isRunRepushThread = true;

  @Getter
  private Cache<Sha256Hash, Boolean> transactionIdCache = CacheBuilder
      .newBuilder().maximumSize(100_000).recordStats().build();

  @Getter
  @Autowired
  private ForkController forkController;

  public WitnessStore getWitnessStore() {
    return this.witnessStore;
  }

  public DynamicPropertiesStore getDynamicPropertiesStore() {
    return this.dynamicPropertiesStore;
  }

  public void setDynamicPropertiesStore(final DynamicPropertiesStore dynamicPropertiesStore) {
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  public WitnessScheduleStore getWitnessScheduleStore() {
    return this.witnessScheduleStore;
  }

  public void setWitnessScheduleStore(final WitnessScheduleStore witnessScheduleStore) {
    this.witnessScheduleStore = witnessScheduleStore;
  }

  public CodeStore getCodeStore() {
    return codeStore;
  }

  public ContractStore getContractStore() {
    return contractStore;
  }

  public VotesStore getVotesStore() {
    return this.votesStore;
  }

  public ProposalStore getProposalStore() {
    return this.proposalStore;
  }

  public ExchangeStore getExchangeStore() {
    return this.exchangeStore;
  }

  public List<TransactionCapsule> getPendingTransactions() {
    return this.pendingTransactions;
  }

  public List<TransactionCapsule> getPoppedTransactions() {
    return this.popedTransactions;
  }

  public BlockingQueue<TransactionCapsule> getRepushTransactions() {
    return repushTransactions;
  }

  // transactions cache
  private List<TransactionCapsule> pendingTransactions;

  // transactions popped
  private List<TransactionCapsule> popedTransactions =
      Collections.synchronizedList(Lists.newArrayList());

  // the capacity is equal to Integer.MAX_VALUE default
  private BlockingQueue<TransactionCapsule> repushTransactions;

  // for test only
  public List<ByteString> getWitnesses() {
    return witnessController.getActiveWitnesses();
  }

  // for test only
  public void addWitness(final ByteString address) {
    List<ByteString> witnessAddresses = witnessController.getActiveWitnesses();
    witnessAddresses.add(address);
    witnessController.setActiveWitnesses(witnessAddresses);
  }

  public BlockCapsule getHead() throws HeaderNotFound {
    List<BlockCapsule> blocks = getBlockStore().getBlockByLatestNum(1);
    if (CollectionUtils.isNotEmpty(blocks)) {
      return blocks.get(0);
    } else {
      logger.info("Header block Not Found");
      throw new HeaderNotFound("Header block Not Found");
    }
  }

  public synchronized BlockId getHeadBlockId() {
    return new BlockId(
        getDynamicPropertiesStore().getLatestBlockHeaderHash(),
        getDynamicPropertiesStore().getLatestBlockHeaderNumber());
  }

  public long getHeadBlockNum() {
    return getDynamicPropertiesStore().getLatestBlockHeaderNumber();
  }

  public long getHeadBlockTimeStamp() {
    return getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
  }


  public void clearAndWriteNeighbours(Set<Node> nodes) {
    this.peersStore.put("neighbours".getBytes(), nodes);
  }

  public Set<Node> readNeighbours() {
    return this.peersStore.get("neighbours".getBytes());
  }

  /**
   * Cycle thread to repush Transactions
   */
  private Runnable repushLoop =
      () -> {
        while (isRunRepushThread) {
          try {
            if (isGeneratingBlock()) {
              TimeUnit.MILLISECONDS.sleep(10L);
              continue;
            }
            TransactionCapsule tx = this.getRepushTransactions().poll(1, TimeUnit.SECONDS);
            if (tx != null) {
              this.rePush(tx);
            }
          } catch (InterruptedException ex) {
            logger.error(ex.getMessage());
            Thread.currentThread().interrupt();
          } catch (Exception ex) {
            logger.error("unknown exception happened in repush loop", ex);
          } catch (Throwable throwable) {
            logger.error("unknown throwable happened in repush loop", throwable);
          }
        }
      };

  public void stopRepushThread() {
    isRunRepushThread = false;
  }

  @PostConstruct
  public void init() {
    revokingStore.disable();
    revokingStore.check();
    this.setWitnessController(WitnessController.createInstance(this));
    this.setProposalController(ProposalController.createInstance(this));
    this.pendingTransactions = Collections.synchronizedList(Lists.newArrayList());
    this.repushTransactions = new LinkedBlockingQueue<>();

    this.initGenesis();
    try {
      this.khaosDb.start(getBlockById(getDynamicPropertiesStore().getLatestBlockHeaderHash()));
    } catch (ItemNotFoundException e) {
      logger.error(
          "Can not find Dynamic highest block from DB! \nnumber={} \nhash={}",
          getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
          getDynamicPropertiesStore().getLatestBlockHeaderHash());
      logger.error(
          "Please delete database directory({}) and restart",
          Args.getInstance().getOutputDirectory());
      System.exit(1);
    } catch (BadItemException e) {
      e.printStackTrace();
      logger.error("DB data broken!");
      logger.error(
          "Please delete database directory({}) and restart",
          Args.getInstance().getOutputDirectory());
      System.exit(1);
    }
    forkController.init(this);
    revokingStore.enable();
    validateSignService = Executors
        .newFixedThreadPool(Args.getInstance().getValidateSignThreadNum());
    repushThread = new Thread(repushLoop);
    repushThread.start();
  }

  public BlockId getGenesisBlockId() {
    return this.genesisBlock.getBlockId();
  }

  public BlockCapsule getGenesisBlock() {
    return genesisBlock;
  }

  /**
   * init genesis block.
   */
  public void initGenesis() {
    this.genesisBlock = BlockUtil.newGenesisBlockCapsule();
    if (this.containBlock(this.genesisBlock.getBlockId())) {
      Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());
    } else {
      if (this.hasBlocks()) {
        logger.error(
            "genesis block modify, please delete database directory({}) and restart",
            Args.getInstance().getOutputDirectory());
        System.exit(1);
      } else {
        logger.info("create genesis block");
        Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());

        blockStore.put(this.genesisBlock.getBlockId().getBytes(), this.genesisBlock);
        this.blockIndexStore.put(this.genesisBlock.getBlockId());

        logger.info("save block: " + this.genesisBlock);
        // init DynamicPropertiesStore
        this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(0);
        this.dynamicPropertiesStore.saveLatestBlockHeaderHash(
            this.genesisBlock.getBlockId().getByteString());
        this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(
            this.genesisBlock.getTimeStamp());
        this.initAccount();
        this.initWitness();
        this.witnessController.initWits();
        this.khaosDb.start(genesisBlock);
        this.updateRecentBlock(genesisBlock);
      }
    }
  }

  /**
   * save account into database.
   */
  public void initAccount() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg
        .getAssets()
        .forEach(
            account -> {
              account.setAccountType("Normal"); // to be set in conf
              final AccountCapsule accountCapsule =
                  new AccountCapsule(
                      account.getAccountName(),
                      ByteString.copyFrom(account.getAddress()),
                      account.getAccountType(),
                      account.getBalance());
              this.accountStore.put(account.getAddress(), accountCapsule);
              this.accountIdIndexStore.put(accountCapsule);
            });
  }

  /**
   * save witnesses into database.
   */
  private void initWitness() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg
        .getWitnesses()
        .forEach(
            key -> {
              byte[] keyAddress = key.getAddress();
              ByteString address = ByteString.copyFrom(keyAddress);

              final AccountCapsule accountCapsule;
              if (!this.accountStore.has(keyAddress)) {
                accountCapsule = new AccountCapsule(ByteString.EMPTY,
                    address, AccountType.AssetIssue, 0L);
              } else {
                accountCapsule = this.accountStore.getUnchecked(keyAddress);
              }
              accountCapsule.setIsWitness(true);
              this.accountStore.put(keyAddress, accountCapsule);

              final WitnessCapsule witnessCapsule =
                  new WitnessCapsule(address, key.getVoteCount(), key.getUrl());
              witnessCapsule.setIsJobs(true);
              this.witnessStore.put(keyAddress, witnessCapsule);
            });
  }

  public AccountStore getAccountStore() {
    return this.accountStore;
  }

  public void adjustBalance(byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = getAccountStore().getUnchecked(accountAddress);
    adjustBalance(account, amount);
  }

  /**
   * judge balance.
   */
  public void adjustBalance(AccountCapsule account, long amount)
      throws BalanceInsufficientException {

    long balance = account.getBalance();
    if (amount == 0) {
      return;
    }

    if (amount < 0 && balance < -amount) {
      throw new BalanceInsufficientException(
          StringUtil.createReadableString(account.createDbKey()) + " insufficient balance");
    }
    account.setBalance(Math.addExact(balance, amount));
    this.getAccountStore().put(account.getAddress().toByteArray(), account);
  }


  public void adjustAllowance(byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = getAccountStore().getUnchecked(accountAddress);
    long allowance = account.getAllowance();
    if (amount == 0) {
      return;
    }

    if (amount < 0 && allowance < -amount) {
      throw new BalanceInsufficientException(
          StringUtil.createReadableString(accountAddress) + " insufficient balance");
    }
    account.setAllowance(allowance + amount);
    this.getAccountStore().put(account.createDbKey(), account);
  }

  void validateTapos(TransactionCapsule transactionCapsule) throws TaposException {
    byte[] refBlockHash = transactionCapsule.getInstance()
        .getRawData().getRefBlockHash().toByteArray();
    byte[] refBlockNumBytes = transactionCapsule.getInstance()
        .getRawData().getRefBlockBytes().toByteArray();
    try {
      byte[] blockHash = this.recentBlockStore.get(refBlockNumBytes).getData();
      if (Arrays.equals(blockHash, refBlockHash)) {
        return;
      } else {
        String str = String.format(
            "Tapos failed, different block hash, %s, %s , recent block %s, solid block %s head block %s",
            ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
            Hex.toHexString(blockHash),
            getSolidBlockId().getString(), getHeadBlockId().getString()).toString();
        logger.info(str);
        throw new TaposException(str);

      }
    } catch (ItemNotFoundException e) {
      String str = String.
          format("Tapos failed, block not found, ref block %s, %s , solid block %s head block %s",
              ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
              getSolidBlockId().getString(), getHeadBlockId().getString()).toString();
      logger.info(str);
      throw new TaposException(str);
    }
  }

  void validateCommon(TransactionCapsule transactionCapsule)
      throws TransactionExpirationException, TooBigTransactionException {
    if (transactionCapsule.getData().length > Constant.TRANSACTION_MAX_BYTE_SIZE) {
      throw new TooBigTransactionException(
          "too big transaction, the size is " + transactionCapsule.getData().length + " bytes");
    }
    long transactionExpiration = transactionCapsule.getExpiration();
    long headBlockTime = getHeadBlockTimeStamp();
    if (transactionExpiration <= headBlockTime ||
        transactionExpiration > headBlockTime + Constant.MAXIMUM_TIME_UNTIL_EXPIRATION) {
      throw new TransactionExpirationException(
          "transaction expiration, transaction expiration time is " + transactionExpiration
              + ", but headBlockTime is " + headBlockTime);
    }
  }

  void validateDup(TransactionCapsule transactionCapsule) throws DupTransactionException {
    if (getTransactionStore().getUnchecked(transactionCapsule.getTransactionId().getBytes())
        != null) {
      logger.debug(ByteArray.toHexString(transactionCapsule.getTransactionId().getBytes()));
      throw new DupTransactionException("dup trans");
    }
  }

  /**
   * push transaction into pending.
   */
  public boolean pushTransaction(final TransactionCapsule trx)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, DupTransactionException, TaposException,
      TooBigTransactionException, TransactionExpirationException,
      ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {

    if (!trx.validateSignature()) {
      throw new ValidateSignatureException("trans sig validate failed");
    }

    synchronized (this) {
      if (!session.valid()) {
        session.setValue(revokingStore.buildSession());
      }

      try (ISession tmpSession = revokingStore.buildSession()) {
        processTransaction(trx, null);
        pendingTransactions.add(trx);
        tmpSession.merge();
      }
    }
    return true;
  }


  public void consumeBandwidth(TransactionCapsule trx, TransactionTrace trace)
      throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
    BandwidthProcessor processor = new BandwidthProcessor(this);
    processor.consume(trx, trace);
  }


  /**
   * when switch fork need erase blocks on fork branch.
   */
  public synchronized void eraseBlock() {
    session.reset();
    try {
      BlockCapsule oldHeadBlock = getBlockById(
          getDynamicPropertiesStore().getLatestBlockHeaderHash());
      logger.info("begin to erase block:" + oldHeadBlock);
      khaosDb.pop();
      revokingStore.pop();
      logger.info("end to erase block:" + oldHeadBlock);
      popedTransactions.addAll(oldHeadBlock.getTransactions());

    } catch (ItemNotFoundException | BadItemException e) {
      logger.warn(e.getMessage(), e);
    }
  }

  public void pushVerifiedBlock(BlockCapsule block) throws ContractValidateException,
      ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
      TransactionExpirationException, TooBigTransactionException, DupTransactionException,
      TaposException, ValidateScheduleException, ReceiptCheckErrException,
      VMIllegalException, TooBigTransactionResultException {
    block.generatedByMyself = true;
    applyBlock(block);
  }

  private void applyBlock(BlockCapsule block) throws ContractValidateException,
      ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
      TransactionExpirationException, TooBigTransactionException, DupTransactionException,
      TaposException, ValidateScheduleException, ReceiptCheckErrException,
      VMIllegalException, TooBigTransactionResultException {
    processBlock(block);
    this.blockStore.put(block.getBlockId().getBytes(), block);
    this.blockIndexStore.put(block.getBlockId());
    updateFork();
  }

  private void switchFork(BlockCapsule newHead)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      ValidateScheduleException, AccountResourceInsufficientException, TaposException,
      TooBigTransactionException, TooBigTransactionResultException, DupTransactionException, TransactionExpirationException,
      NonCommonBlockException, ReceiptCheckErrException,
      VMIllegalException {
    Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> binaryTree;
    try {
      binaryTree =
          khaosDb.getBranch(
              newHead.getBlockId(), getDynamicPropertiesStore().getLatestBlockHeaderHash());
    } catch (NonCommonBlockException e) {
      logger.info(
          "there is not the most recent common ancestor, need to remove all blocks in the fork chain.");
      BlockCapsule tmp = newHead;
      while (tmp != null) {
        khaosDb.removeBlk(tmp.getBlockId());
        tmp = khaosDb.getBlock(tmp.getParentHash());
      }

      throw e;
    }
    if (CollectionUtils.isNotEmpty(binaryTree.getValue())) {
      while (!getDynamicPropertiesStore()
          .getLatestBlockHeaderHash()
          .equals(binaryTree.getValue().peekLast().getParentHash())) {
        eraseBlock();
      }
    }

    if (CollectionUtils.isNotEmpty(binaryTree.getKey())) {
      List<KhaosBlock> first = new ArrayList<>(binaryTree.getKey());
      Collections.reverse(first);
      for (KhaosBlock item : first) {
        Exception exception = null;
        // todo  process the exception carefully later
        try (ISession tmpSession = revokingStore.buildSession()) {
          applyBlock(item.getBlk());
          tmpSession.commit();
        } catch (AccountResourceInsufficientException
            | ValidateSignatureException
            | ContractValidateException
            | ContractExeException
            | TaposException
            | DupTransactionException
            | TransactionExpirationException
            | ReceiptCheckErrException
            | TooBigTransactionException
            | TooBigTransactionResultException
            | ValidateScheduleException
            | VMIllegalException e) {
          logger.warn(e.getMessage(), e);
          exception = e;
          throw e;
        } finally {
          if (exception != null) {
            logger.warn("switch back because exception thrown while switching forks. " + exception
                    .getMessage(),
                exception);
            first.forEach(khaosBlock -> khaosDb.removeBlk(khaosBlock.getBlk().getBlockId()));
            khaosDb.setHead(binaryTree.getValue().peekFirst());

            while (!getDynamicPropertiesStore()
                .getLatestBlockHeaderHash()
                .equals(binaryTree.getValue().peekLast().getParentHash())) {
              eraseBlock();
            }

            List<KhaosBlock> second = new ArrayList<>(binaryTree.getValue());
            Collections.reverse(second);
            for (KhaosBlock khaosBlock : second) {
              // todo  process the exception carefully later
              try (ISession tmpSession = revokingStore.buildSession()) {
                applyBlock(khaosBlock.getBlk());
                tmpSession.commit();
              } catch (AccountResourceInsufficientException
                  | ValidateSignatureException
                  | ContractValidateException
                  | ContractExeException
                  | TaposException
                  | DupTransactionException
                  | TransactionExpirationException
                  | TooBigTransactionException
                  | ValidateScheduleException e) {
                logger.warn(e.getMessage(), e);
              }
            }
          }
        }
      }
    }
  }


  /**
   * save a block.
   */
  public synchronized void pushBlock(final BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException,
      TaposException, TooBigTransactionException, TooBigTransactionResultException, DupTransactionException, TransactionExpirationException,
      BadNumberBlockException, BadBlockException, NonCommonBlockException,
      ReceiptCheckErrException, VMIllegalException {
    try (PendingManager pm = new PendingManager(this)) {

      if (!block.generatedByMyself) {
        if (!block.validateSignature()) {
          logger.warn("The signature is not validated.");
          throw new BadBlockException("The signature is not validated");
        }

        if (!block.calcMerkleRoot().equals(block.getMerkleRoot())) {
          logger.warn(
              "The merkle root doesn't match, Calc result is "
                  + block.calcMerkleRoot()
                  + " , the headers is "
                  + block.getMerkleRoot());
          throw new BadBlockException("The merkle hash is not validated");
        }
      }

      BlockCapsule newBlock = this.khaosDb.push(block);

      // DB don't need lower block
      if (getDynamicPropertiesStore().getLatestBlockHeaderHash() == null) {
        if (newBlock.getNum() != 0) {
          return;
        }
      } else {
        if (newBlock.getNum() <= getDynamicPropertiesStore().getLatestBlockHeaderNumber()) {
          return;
        }

        // switch fork
        if (!newBlock
            .getParentHash()
            .equals(getDynamicPropertiesStore().getLatestBlockHeaderHash())) {
          logger.warn(
              "switch fork! new head num = {}, blockid = {}",
              newBlock.getNum(),
              newBlock.getBlockId());

          logger.warn(
              "******** before switchFork ******* push block: "
                  + block.getShortString()
                  + ", new block:"
                  + newBlock.getShortString()
                  + ", dynamic head num: "
                  + dynamicPropertiesStore.getLatestBlockHeaderNumber()
                  + ", dynamic head hash: "
                  + dynamicPropertiesStore.getLatestBlockHeaderHash()
                  + ", dynamic head timestamp: "
                  + dynamicPropertiesStore.getLatestBlockHeaderTimestamp()
                  + ", khaosDb head: "
                  + khaosDb.getHead()
                  + ", khaosDb miniStore size: "
                  + khaosDb.getMiniStore().size()
                  + ", khaosDb unlinkMiniStore size: "
                  + khaosDb.getMiniUnlinkedStore().size());

          switchFork(newBlock);
          logger.info("save block: " + newBlock);

          logger.warn(
              "******** after switchFork ******* push block: "
                  + block.getShortString()
                  + ", new block:"
                  + newBlock.getShortString()
                  + ", dynamic head num: "
                  + dynamicPropertiesStore.getLatestBlockHeaderNumber()
                  + ", dynamic head hash: "
                  + dynamicPropertiesStore.getLatestBlockHeaderHash()
                  + ", dynamic head timestamp: "
                  + dynamicPropertiesStore.getLatestBlockHeaderTimestamp()
                  + ", khaosDb head: "
                  + khaosDb.getHead()
                  + ", khaosDb miniStore size: "
                  + khaosDb.getMiniStore().size()
                  + ", khaosDb unlinkMiniStore size: "
                  + khaosDb.getMiniUnlinkedStore().size());

          return;
        }
        try (ISession tmpSession = revokingStore.buildSession()) {
          applyBlock(newBlock);
          tmpSession.commit();
        } catch (Throwable throwable) {
          logger.error(throwable.getMessage(), throwable);
          khaosDb.removeBlk(block.getBlockId());
          throw throwable;
        }
      }
      logger.debug("save block: " + newBlock);
    }
  }

  public void updateDynamicProperties(BlockCapsule block) {
    long slot = 1;
    if (block.getNum() != 1) {
      slot = witnessController.getSlotAtTime(block.getTimeStamp());
    }
    for (int i = 1; i < slot; ++i) {
      if (!witnessController.getScheduledWitness(i).equals(block.getWitnessAddress())) {
        WitnessCapsule w =
            this.witnessStore
                .getUnchecked(StringUtil.createDbKey(witnessController.getScheduledWitness(i)));
        w.setTotalMissed(w.getTotalMissed() + 1);
        this.witnessStore.put(w.createDbKey(), w);
        logger.info(
            "{} miss a block. totalMissed = {}", w.createReadableString(), w.getTotalMissed());
      }
      this.dynamicPropertiesStore.applyBlock(false);
    }
    this.dynamicPropertiesStore.applyBlock(true);

    if (slot <= 0) {
      logger.warn("missedBlocks [" + slot + "] is illegal");
    }

    logger.debug("update head, num = {}", block.getNum());
    this.dynamicPropertiesStore.saveLatestBlockHeaderHash(block.getBlockId().getByteString());

    this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(block.getNum());
    this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(block.getTimeStamp());
    revokingStore.setMaxSize((int) (dynamicPropertiesStore.getLatestBlockHeaderNumber()
        - dynamicPropertiesStore.getLatestSolidifiedBlockNum()
        + 1));
    khaosDb.setMaxSize((int)
        (dynamicPropertiesStore.getLatestBlockHeaderNumber()
            - dynamicPropertiesStore.getLatestSolidifiedBlockNum()
            + 1));
  }

  /**
   * Get the fork branch.
   */
  public LinkedList<BlockId> getBlockChainHashesOnFork(final BlockId forkBlockHash)
      throws NonCommonBlockException {
    final Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> branch =
        this.khaosDb.getBranch(
            getDynamicPropertiesStore().getLatestBlockHeaderHash(), forkBlockHash);

    LinkedList<KhaosBlock> blockCapsules = branch.getValue();

    if (blockCapsules.isEmpty()) {
      logger.info("empty branch {}", forkBlockHash);
      return Lists.newLinkedList();
    }

    LinkedList<BlockId> result = blockCapsules.stream()
        .map(KhaosBlock::getBlk)
        .map(BlockCapsule::getBlockId)
        .collect(Collectors.toCollection(LinkedList::new));

    result.add(blockCapsules.peekLast().getBlk().getParentBlockId());

    return result;
  }

  /**
   * judge id.
   *
   * @param blockHash blockHash
   */
  public boolean containBlock(final Sha256Hash blockHash) {
    try {
      return this.khaosDb.containBlockInMiniStore(blockHash)
          || blockStore.get(blockHash.getBytes()) != null;
    } catch (ItemNotFoundException e) {
      return false;
    } catch (BadItemException e) {
      return false;
    }
  }

  public boolean containBlockInMainChain(BlockId blockId) {
    try {
      return blockStore.get(blockId.getBytes()) != null;
    } catch (ItemNotFoundException e) {
      return false;
    } catch (BadItemException e) {
      return false;
    }
  }

  public void setBlockReference(TransactionCapsule trans) {
    byte[] headHash = getDynamicPropertiesStore().getLatestBlockHeaderHash().getBytes();
    long headNum = getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    trans.setReference(headNum, headHash);
  }

  /**
   * Get a BlockCapsule by id.
   */
  public BlockCapsule getBlockById(final Sha256Hash hash)
      throws BadItemException, ItemNotFoundException {
    return this.khaosDb.containBlock(hash)
        ? this.khaosDb.getBlock(hash)
        : blockStore.get(hash.getBytes());
  }


  /**
   * judge has blocks.
   */
  public boolean hasBlocks() {
    return blockStore.iterator().hasNext() || this.khaosDb.hasData();
  }

  /**
   * Process transaction.
   */
  public boolean processTransaction(final TransactionCapsule trxCap, BlockCapsule blockCap)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, TransactionExpirationException, TooBigTransactionException, TooBigTransactionResultException,
      DupTransactionException, TaposException, ReceiptCheckErrException, VMIllegalException {
    if (trxCap == null) {
      return false;
    }

    validateTapos(trxCap);
    validateCommon(trxCap);

    if (trxCap.getInstance().getRawData().getContractList().size() != 1) {
      throw new ContractSizeNotEqualToOneException(
          "act size should be exactly 1, this is extend feature");
    }
    forkController.hardFork(trxCap);

    validateDup(trxCap);

    if (!trxCap.validateSignature()) {
      throw new ValidateSignatureException("trans sig validate failed");
    }

    TransactionTrace trace = new TransactionTrace(trxCap, this);
    trxCap.setTrxTrace(trace);

    consumeBandwidth(trxCap, trace);

    DepositImpl deposit = DepositImpl.createRoot(this);
    Runtime runtime = new Runtime(trace, blockCap, deposit, new ProgramInvokeFactoryImpl());
    if (runtime.isCallConstant()) {
      throw new VMIllegalException("cannot call constant method ");
    }
    trace.init();
    trace.exec(runtime);

    if (Objects.nonNull(blockCap)) {
      trace.setResult(runtime);
      if (!blockCap.getInstance().getBlockHeader().getWitnessSignature().isEmpty()) {
        if (trace.checkNeedRetry()) {
          String txId = Hex.toHexString(trxCap.getTransactionId().getBytes());
          logger.info("Retry for tx id: {}", txId);
          deposit = DepositImpl.createRoot(this);
          runtime = new Runtime(trace, blockCap, deposit, new ProgramInvokeFactoryImpl());
          trace.init();
          trace.exec(runtime);
          trace.setResult(runtime);
          logger.info("Retry result for tx id: {}, tx resultCode in receipt: {}",
              txId, trace.getReceipt().getResult());
        }
        trace.check();
      }
    }

    trace.finalization(runtime);
    if (Objects.nonNull(blockCap)) {
      if (getDynamicPropertiesStore().supportVM()) {
        trxCap.setResult(runtime);
      }
    }
    transactionStore.put(trxCap.getTransactionId().getBytes(), trxCap);

    ReceiptCapsule traceReceipt = trace.getReceipt();

    TransactionInfoCapsule transactionInfo = TransactionInfoCapsule
        .buildInstance(trxCap, blockCap, runtime, traceReceipt);

    transactionHistoryStore.put(trxCap.getTransactionId().getBytes(), transactionInfo);

    if (Objects.nonNull(blockCap)) {
      sendEventLog(runtime.getResult().getContractAddress(),
          transactionInfo.getInstance().getLogList(), blockCap.getInstance(), transactionInfo);
    }
    return true;
  }

  private void sendEventLog(byte[] contractAddress, List<org.tron.protos.Protocol.TransactionInfo.Log> logList, Block block, TransactionInfoCapsule transactionInfoCapsule) {
    if (block == null || block.getBlockHeader().getWitnessSignature().isEmpty()) {
      return;
    }
    try {
      Protocol.SmartContract.ABI abi = abiCache.getIfPresent(contractAddress);
      if (abi == null) {
        abi = getContractStore().getABI(contractAddress);
        if (abi == null) {
          return;
        }
        abiCache.put(contractAddress, abi);
      }
      Protocol.SmartContract.ABI finalAbi = abi;
      logList.forEach(log -> {
        finalAbi.getEntrysList().forEach(abiEntry -> {
          if (abiEntry.getType() != Protocol.SmartContract.ABI.Entry.EntryType.Event) {
            return;
          }
          //parse abi
          String entryName = abiEntry.getName();
          List<TypeReference<?>> typeList = new ArrayList<>();
          List<String> nameList = new ArrayList<>();
          abiEntry.getInputsList().forEach(input -> {
            nameList.add(input.getName());
            TypeReference<?> tr = AbiTypes.getTypeReference(input.getType(), input.getIndexed());
            typeList.add(tr);
          });
          JSONObject resultJsonObject = new JSONObject();
          JSONObject rawJsonObject = new JSONObject();

          String eventHexString = Hex.toHexString(log.getTopicsList().get(0).toByteArray());
          JSONArray rawTopicsJsonArray = new JSONArray();
          rawTopicsJsonArray.add(eventHexString);

          Event event = new Event(entryName, typeList);
          String rawLogData = ByteArray.toHexString(log.getData().toByteArray());
          List<Type> nonIndexedValues = FunctionReturnDecoder.decode(rawLogData, event.getNonIndexedParameters());
          List<Type> indexedValues = new ArrayList<>();

          List<TypeReference<Type>> indexedParameters = event.getIndexedParameters();
          for (int i = 0; i < indexedParameters.size(); i++) {
            String topicHexString = Hex.toHexString(log.getTopicsList().get(i + 1).toByteArray());
            rawTopicsJsonArray.add(topicHexString);
            Type value = FunctionReturnDecoder.decodeIndexedValue(topicHexString, indexedParameters.get(i));
            indexedValues.add(value);
          }
          int counter = 0;
          int indexedCounter = 0;
          int nonIndexedCounter = 0;
          for (TypeReference<?> typeReference : typeList) {
            if(typeReference.isIndexed()) {
              resultJsonObject.put(nameList.get(counter),
                  (indexedValues.get(indexedCounter) instanceof BytesType)
                      ? Hex.toHexString((byte[]) indexedValues.get(indexedCounter).getValue())
                      : indexedValues.get(indexedCounter).getValue());
              indexedCounter++;
            } else {
              resultJsonObject.put(nameList.get(counter), (nonIndexedValues.get(nonIndexedCounter) instanceof BytesType)
                  ? Hex.toHexString((byte[]) nonIndexedValues.get(nonIndexedCounter).getValue())
                  : nonIndexedValues.get(nonIndexedCounter).getValue());
              nonIndexedCounter++;
            }
            counter++;
          }
          rawJsonObject.put("topics", rawTopicsJsonArray);
          rawJsonObject.put("data", rawLogData);

          long blockNumber = block.getBlockHeader().getRawData().getNumber();
          long blockTimestamp = block.getBlockHeader().getRawData().getTimestamp();
          logger.info("Event blockNumber:{} blockTimestamp:{} contractAddress:{} eventName:{} returnValues:{} raw:{} txId:{}",
                  blockNumber, blockTimestamp,
                  Wallet.encode58Check(contractAddress), entryName, resultJsonObject, rawJsonObject,
                  Hex.toHexString(transactionInfoCapsule.getId()));

//          EventLogEntity eventLogEntity = new EventLogEntity(blockNumber, blockTimestamp,
//                  Wallet.encode58Check(contractAddress), entryName, resultJsonObject, rawJsonObject,
//                  Hex.toHexString(transactionInfoCapsule.getId()));
//          eventLogService.insertEventLog(eventLogEntity);
        });
      });
    } catch (Exception e) {
      logger.error("sendEventLog Failed {}", e);
    }
  }
  /**
   * Get the block id from the number.
   */
  public BlockId getBlockIdByNum(final long num) throws ItemNotFoundException {
    return this.blockIndexStore.get(num);
  }

  public BlockCapsule getBlockByNum(final long num) throws ItemNotFoundException, BadItemException {
    return getBlockById(getBlockIdByNum(num));
  }

  /**
   * Generate a block.
   */
  public synchronized BlockCapsule generateBlock(
      final WitnessCapsule witnessCapsule, final long when, final byte[] privateKey,
      Boolean lastHeadBlockIsMaintenanceBefore)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException {

    //check that the first block after the maintenance period has just been processed
    if (lastHeadBlockIsMaintenanceBefore != lastHeadBlockIsMaintenance()) {
      if (!witnessController.validateWitnessSchedule(witnessCapsule.getAddress(), when)) {
        logger.info("It's not my turn, "
            + "and the first block after the maintenance period has just been processed");
        return null;
      }
    }

    final long timestamp = this.dynamicPropertiesStore.getLatestBlockHeaderTimestamp();
    final long number = this.dynamicPropertiesStore.getLatestBlockHeaderNumber();
    final Sha256Hash preHash = this.dynamicPropertiesStore.getLatestBlockHeaderHash();

    // judge create block time
    if (when < timestamp) {
      throw new IllegalArgumentException("generate block timestamp is invalid.");
    }

    long postponedTrxCount = 0;

    final BlockCapsule blockCapsule =
        new BlockCapsule(number + 1, preHash, when, witnessCapsule.getAddress());
    blockCapsule.generatedByMyself = true;
    session.reset();
    session.setValue(revokingStore.buildSession());

    Iterator iterator = pendingTransactions.iterator();
    while (iterator.hasNext()) {
      TransactionCapsule trx = (TransactionCapsule) iterator.next();
      if (DateTime.now().getMillis() - when
          > ChainConstant.BLOCK_PRODUCED_INTERVAL * 0.5
          * Args.getInstance().getBlockProducedTimeOut()
          / 100) {
        logger.warn("Processing transaction time exceeds the 50% producing time。");
        break;
      }
      // check the block size
      if ((blockCapsule.getInstance().getSerializedSize() + trx.getSerializedSize() + 3)
          > ChainConstant.BLOCK_SIZE) {
        postponedTrxCount++;
        continue;
      }
      // apply transaction
      try (ISession tmpSeesion = revokingStore.buildSession()) {
        processTransaction(trx, blockCapsule);
        tmpSeesion.merge();
        // push into block
        blockCapsule.addTransaction(trx);
        iterator.remove();
      } catch (ContractExeException e) {
        logger.info("contract not processed during execute");
        logger.debug(e.getMessage(), e);
      } catch (ContractValidateException e) {
        logger.info("contract not processed during validate");
        logger.debug(e.getMessage(), e);
      } catch (TaposException e) {
        logger.info("contract not processed during TaposException");
        logger.debug(e.getMessage(), e);
      } catch (DupTransactionException e) {
        logger.info("contract not processed during DupTransactionException");
        logger.debug(e.getMessage(), e);
      } catch (TooBigTransactionException e) {
        logger.info("contract not processed during TooBigTransactionException");
        logger.debug(e.getMessage(), e);
      } catch (TooBigTransactionResultException e) {
        logger.info("contract not processed during TooBigTransactionResultException");
        logger.debug(e.getMessage(), e);
      } catch (TransactionExpirationException e) {
        logger.info("contract not processed during TransactionExpirationException");
        logger.debug(e.getMessage(), e);
      } catch (AccountResourceInsufficientException e) {
        logger.info("contract not processed during AccountResourceInsufficientException");
        logger.debug(e.getMessage(), e);
      } catch (ValidateSignatureException e) {
        logger.info("contract not processed during ValidateSignatureException");
        logger.debug(e.getMessage(), e);
      } catch (ReceiptCheckErrException e) {
        logger.info("OutOfSlotTime exception: {}", e.getMessage());
        logger.debug(e.getMessage(), e);
      } catch (VMIllegalException e) {
        logger.warn(e.getMessage(), e);
      }
    }

    session.reset();

    if (postponedTrxCount > 0) {
      logger.info("{} transactions over the block size limit", postponedTrxCount);
    }

    logger.debug(
        "postponedTrxCount[" + postponedTrxCount + "],TrxLeft[" + pendingTransactions.size()
            + "],repushTrxCount[" + repushTransactions.size() + "]");
    blockCapsule.setMerkleRoot();
    blockCapsule.sign(privateKey);

    try {
      this.pushBlock(blockCapsule);
      return blockCapsule;
    } catch (TaposException e) {
      logger.info("contract not processed during TaposException");
    } catch (TooBigTransactionException e) {
      logger.info("contract not processed during TooBigTransactionException");
    } catch (DupTransactionException e) {
      logger.info("contract not processed during DupTransactionException");
    } catch (TransactionExpirationException e) {
      logger.info("contract not processed during TransactionExpirationException");
    } catch (BadNumberBlockException e) {
      logger.info("generate block using wrong number");
    } catch (BadBlockException e) {
      logger.info("block exception");
    } catch (NonCommonBlockException e) {
      logger.info("non common exception");
    } catch (ReceiptCheckErrException e) {
      logger.info("OutOfSlotTime exception: {}", e.getMessage());
      logger.debug(e.getMessage(), e);
    } catch (VMIllegalException e) {
      logger.warn(e.getMessage(), e);
    } catch (TooBigTransactionResultException e) {
      logger.info("contract not processed during TooBigTransactionResultException");
    }

    return null;
  }


  public TransactionStore getTransactionStore() {
    return this.transactionStore;
  }


  public TransactionHistoryStore getTransactionHistoryStore() {
    return this.transactionHistoryStore;
  }

  public BlockStore getBlockStore() {
    return this.blockStore;
  }


  /**
   * process block.
   */
  public void processBlock(BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, TaposException, TooBigTransactionException,
      DupTransactionException, TransactionExpirationException, ValidateScheduleException,
      ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {
    // todo set revoking db max size.

    // checkWitness
    if (!witnessController.validateWitnessSchedule(block)) {
      throw new ValidateScheduleException("validateWitnessSchedule error");
    }

    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      if (block.generatedByMyself) {
        transactionCapsule.setVerified(true);
      }
      processTransaction(transactionCapsule, block);
    }

    boolean needMaint = needMaintenance(block.getTimeStamp());
    if (needMaint) {
      if (block.getNum() == 1) {
        this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
      } else {
        this.processMaintenance(block);
      }
    }
    this.updateDynamicProperties(block);
    this.updateSignedWitness(block);
    this.updateLatestSolidifiedBlock();
    this.updateTransHashCache(block);
    updateMaintenanceState(needMaint);
    updateRecentBlock(block);

  }

  private void updateTransHashCache(BlockCapsule block) {
    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      this.transactionIdCache.put(transactionCapsule.getTransactionId(), true);
    }
  }

  public void updateRecentBlock(BlockCapsule block) {
    this.recentBlockStore.put(ByteArray.subArray(
        ByteArray.fromLong(block.getNum()), 6, 8),
        new BytesCapsule(ByteArray.subArray(block.getBlockId().getBytes(), 8, 16)));
  }

  /**
   * update the latest solidified block.
   */
  public void updateLatestSolidifiedBlock() {
    List<Long> numbers =
        witnessController
            .getActiveWitnesses()
            .stream()
            .map(address -> witnessController.getWitnesseByAddress(address).getLatestBlockNum())
            .sorted()
            .collect(Collectors.toList());

    long size = witnessController.getActiveWitnesses().size();
    int solidifiedPosition = (int) (size * (1 - SOLIDIFIED_THRESHOLD * 1.0 / 100));
    if (solidifiedPosition < 0) {
      logger.warn(
          "updateLatestSolidifiedBlock error, solidifiedPosition:{},wits.size:{}",
          solidifiedPosition,
          size);
      return;
    }
    long latestSolidifiedBlockNum = numbers.get(solidifiedPosition);
    //if current value is less than the previous value，keep the previous value.
    if (latestSolidifiedBlockNum < getDynamicPropertiesStore().getLatestSolidifiedBlockNum()) {
      logger.warn("latestSolidifiedBlockNum = 0,LatestBlockNum:{}", numbers);
      return;
    }
    getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(latestSolidifiedBlockNum);
    logger.debug("update solid block, num = {}", latestSolidifiedBlockNum);
  }

  public void updateFork() {
    if (forkController.shouldBeForked()) {
      return;
    }

    try {
      long latestSolidifiedBlockNum = dynamicPropertiesStore.getLatestSolidifiedBlockNum();
      BlockCapsule solidifiedBlock = getBlockByNum(latestSolidifiedBlockNum);
      forkController.update(solidifiedBlock);
    } catch (ItemNotFoundException | BadItemException e) {
      logger.warn("solidified block not found");
    }
  }

  public long getSyncBeginNumber() {
    logger.info("headNumber:" + dynamicPropertiesStore.getLatestBlockHeaderNumber());
    logger.info(
        "syncBeginNumber:"
            + (dynamicPropertiesStore.getLatestBlockHeaderNumber() - revokingStore.size()));
    logger.info("solidBlockNumber:" + dynamicPropertiesStore.getLatestSolidifiedBlockNum());
    return dynamicPropertiesStore.getLatestBlockHeaderNumber() - revokingStore.size();
  }

  public BlockId getSolidBlockId() {
    try {
      long num = dynamicPropertiesStore.getLatestSolidifiedBlockNum();
      return getBlockIdByNum(num);
    } catch (Exception e) {
      return getGenesisBlockId();
    }
  }

  /**
   * Determine if the current time is maintenance time.
   */
  public boolean needMaintenance(long blockTime) {
    return this.dynamicPropertiesStore.getNextMaintenanceTime() <= blockTime;
  }

  /**
   * Perform maintenance.
   */
  private void processMaintenance(BlockCapsule block) {
    proposalController.processProposals();
    witnessController.updateWitness();
    this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
    forkController.reset();
  }

  /**
   * @param block the block update signed witness. set witness who signed block the 1. the latest
   * block num 2. pay the trx to witness. 3. the latest slot num.
   */
  public void updateSignedWitness(BlockCapsule block) {
    // TODO: add verification
    WitnessCapsule witnessCapsule =
        witnessStore.getUnchecked(
            block.getInstance().getBlockHeader().getRawData().getWitnessAddress().toByteArray());
    witnessCapsule.setTotalProduced(witnessCapsule.getTotalProduced() + 1);
    witnessCapsule.setLatestBlockNum(block.getNum());
    witnessCapsule.setLatestSlotNum(witnessController.getAbSlotAtTime(block.getTimeStamp()));

    // Update memory witness status
    WitnessCapsule wit = witnessController.getWitnesseByAddress(block.getWitnessAddress());
    if (wit != null) {
      wit.setTotalProduced(witnessCapsule.getTotalProduced() + 1);
      wit.setLatestBlockNum(block.getNum());
      wit.setLatestSlotNum(witnessController.getAbSlotAtTime(block.getTimeStamp()));
    }

    this.getWitnessStore().put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);

    try {
      adjustAllowance(witnessCapsule.getAddress().toByteArray(),
          getDynamicPropertiesStore().getWitnessPayPerBlock());
    } catch (BalanceInsufficientException e) {
      logger.warn(e.getMessage(), e);
    }

    logger.debug(
        "updateSignedWitness. witness address:{}, blockNum:{}, totalProduced:{}",
        witnessCapsule.createReadableString(),
        block.getNum(),
        witnessCapsule.getTotalProduced());
  }

  public void updateMaintenanceState(boolean needMaint) {
    if (needMaint) {
      getDynamicPropertiesStore().saveStateFlag(1);
    } else {
      getDynamicPropertiesStore().saveStateFlag(0);
    }
  }

  public boolean lastHeadBlockIsMaintenance() {
    return getDynamicPropertiesStore().getStateFlag() == 1;
  }

  // To be added
  public long getSkipSlotInMaintenance() {
    return getDynamicPropertiesStore().getMaintenanceSkipSlots();
  }

  public AssetIssueStore getAssetIssueStore() {
    return assetIssueStore;
  }

  public void setAssetIssueStore(AssetIssueStore assetIssueStore) {
    this.assetIssueStore = assetIssueStore;
  }

  public void setBlockIndexStore(BlockIndexStore indexStore) {
    this.blockIndexStore = indexStore;
  }

  public AccountIdIndexStore getAccountIdIndexStore() {
    return this.accountIdIndexStore;
  }

  public void setAccountIdIndexStore(AccountIdIndexStore indexStore) {
    this.accountIdIndexStore = indexStore;
  }

  public AccountIndexStore getAccountIndexStore() {
    return this.accountIndexStore;
  }

  public void setAccountIndexStore(AccountIndexStore indexStore) {
    this.accountIndexStore = indexStore;
  }

  public void closeAllStore() {
    logger.debug("******** begin to close db ********");
    closeOneStore(accountStore);
    closeOneStore(blockStore);
    closeOneStore(blockIndexStore);
    closeOneStore(accountIdIndexStore);
    closeOneStore(accountIndexStore);
    closeOneStore(witnessStore);
    closeOneStore(witnessScheduleStore);
    closeOneStore(assetIssueStore);
    closeOneStore(dynamicPropertiesStore);
    closeOneStore(transactionStore);
    closeOneStore(codeStore);
    closeOneStore(contractStore);
    closeOneStore(storageRowStore);
    closeOneStore(exchangeStore);
    closeOneStore(peersStore);
    closeOneStore(proposalStore);
    closeOneStore(recentBlockStore);
    closeOneStore(transactionHistoryStore);
    closeOneStore(votesStore);
    logger.debug("******** end to close db ********");
  }

  private void closeOneStore(ITronChainBase database) {
    logger.debug("******** begin to close " + database.getName() + " ********");
    try {
      database.close();
    } catch (Exception e) {
      logger.error("failed to close  " + database.getName() + ". " + e);
    } finally {
      logger.debug("******** end to close " + database.getName() + " ********");
    }
  }

  public boolean isTooManyPending() {
    if (getPendingTransactions().size() + getRepushTransactions().size()
        > MAX_TRANSACTION_PENDING) {
      return true;
    }
    return false;
  }

  public boolean isGeneratingBlock() {
    if (Args.getInstance().isWitness()) {
      return witnessController.isGeneratingBlock();
    }
    return false;
  }

  private static class ValidateSignTask implements Callable<Boolean> {

    private TransactionCapsule trx;
    private CountDownLatch countDownLatch;

    ValidateSignTask(TransactionCapsule trx, CountDownLatch countDownLatch) {
      this.trx = trx;
      this.countDownLatch = countDownLatch;
    }

    @Override
    public Boolean call() throws ValidateSignatureException {
      try {
        trx.validateSignature();
      } catch (ValidateSignatureException e) {
        throw e;
      } finally {
        countDownLatch.countDown();
      }
      return true;
    }
  }

  public synchronized void preValidateTransactionSign(BlockCapsule block)
      throws InterruptedException, ValidateSignatureException {
    logger.info("PreValidate Transaction Sign, size:" + block.getTransactions().size()
        + ",block num:" + block.getNum());
    int transSize = block.getTransactions().size();
    CountDownLatch countDownLatch = new CountDownLatch(transSize);
    List<Future<Boolean>> futures = new ArrayList<>(transSize);

    for (TransactionCapsule transaction : block.getTransactions()) {
      Future<Boolean> future = validateSignService
          .submit(new ValidateSignTask(transaction, countDownLatch));
      futures.add(future);
    }
    countDownLatch.await();

    for (Future<Boolean> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        throw new ValidateSignatureException(e.getCause().getMessage());
      }
    }
  }

  public void rePush(TransactionCapsule tx) {

    try {
      if (transactionStore.get(tx.getTransactionId().getBytes()) != null) {
        return;
      }
    } catch (BadItemException e) {
      // do nothing
    }

    try {
      this.pushTransaction(tx);
    } catch (ValidateSignatureException e) {
      logger.debug(e.getMessage(), e);
    } catch (ContractValidateException e) {
      logger.debug(e.getMessage(), e);
    } catch (ContractExeException e) {
      logger.debug(e.getMessage(), e);
    } catch (AccountResourceInsufficientException e) {
      logger.debug(e.getMessage(), e);
    } catch (DupTransactionException e) {
      logger.debug("pending manager: dup trans", e);
    } catch (TaposException e) {
      logger.debug("pending manager: tapos exception", e);
    } catch (TooBigTransactionException e) {
      logger.debug("too big transaction");
    } catch (TransactionExpirationException e) {
      logger.debug("expiration transaction");
    } catch (ReceiptCheckErrException e) {
      logger.debug("outOfSlotTime transaction");
    } catch (VMIllegalException e) {
      logger.debug(e.getMessage(), e);
    } catch (TooBigTransactionResultException e) {
      logger.debug("too big transaction result");
    }
  }
}
