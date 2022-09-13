/** IO bundle definition for an Arbiter, which takes some number of ready-valid inputs and outputs
 * (selects) at most one.
 * @groupdesc Signals The actual hardware fields of the Bundle
 *
 * @param gen data type
 * @param n number of inputs
 */
class ArbiterIOVar[T <: Data](private val gen: T, val n: Int) (implicit p: Parameters) extends Bundle {
  // See github.com/freechipsproject/chisel3/issues/765 for why gen is a private val and proposed replacement APIs.

  /** Input data, one per potential sender
   *
   * @group Signals
   */
  val in = Flipped(Vec(n, Decoupled(gen)))

  /** Output data after arbitration
   *
   * @group Signals
   */
  val out = Decoupled(gen)

  /** One-Hot vector indicating which output was chosen
   *
   * @group Signals
   */
  val chosen = Output(UInt(log2Ceil(n).W))
  //edited
  val rob_head = Input(UInt(32.W))

  val brupdate = Input(new BrUpdateInfo())
  //val brupdate  = Wire(new BrUpdateInfo)

  val kill = Input(Bool())
}




/**
 * Arbiter Control determining which producer has access
 */
/**
private object ArbiterCtrlVarTime {
  def apply(request: Vec(n, Decoupled(ExeUnitResp))): Seq[Bool] = {
    request.map(_.valid).length match{
      case 0 => Seq()
      case 1 => Seq(true.B)
      case _ => varTime(request)
    }


    /** true.B +: request.tail.init.scanLeft(request.head)(_ || _).map(!_) */
  }

/**
  def varTime(request: Vec(n, Decoupled(ExeUnitResp))): Seq[Bool] = {
    //val newReq: Seq[Bool] = Seq.empty[bool]
    val oldest = Seq()
    oldest :+ request(request.length-1)
    //find oldest in request
    for (i <- 0 to request.length-1 by +1) {
      if (IsOlder(request(i).uop.rob_idx, oldest(0).uop.rob_idx, io.rob_head)){
        oldest +: io.in.slice(i,i)
      }
    }
    //after loop oldest is oldest(0)
    return oldest
  }
  */
}
*/

/**
 *Arbiter with variable time execution
 *edited default case in Arbiterctl
 */
class ArbiterVarTime[T <: Data](val gen: ExeUnitResp, val n: Int) (implicit p: Parameters) extends Module {
  val io = IO(new ArbiterIOVar(gen, n))

  io.chosen := (n - 1).asUInt
  io.out.bits := io.in(n - 1).bits
  for (i <- n - 2 to 0 by -1) {
    when(io.in(i).valid) {
      io.chosen := i.asUInt
      io.out.bits := io.in(i).bits
    }
  }

  // set bool depending on signal from queue or input is chosen
  val qout = WireInit(false.B)

  val uint = 0.U
  //seq that is delivered to grant
  val returngrant = VecInit(Seq.fill(n){false.B})
  //val to save oldest signal
  val oldest = Reg(UInt())
  oldest := PriorityEncoder(io.in.map(_.valid))

  //#############################################################################Queue

  //length of queue
  val count = 64
  //create queue
  val queue = Module(new Queue(gen, entries = count, n))

  queue.io.rob_head := io.rob_head

  /*val queue = Module(new BranchKillableQueue(new ExeUnitResp(dataWidth),
    entries = 64))*/
  val in_valid = WireInit(VecInit(Seq.fill(n) {false.B}))//Wire(Bool())//io.in(0).valid//Wire(Output(Bool()))//false.B//Bool()
  val in_bitsUop = WireInit(VecInit(Seq.fill(n) {NullMicroOp}))//NullMicroOp//new MicroOp
  val in_bitsData = WireInit(VecInit(Seq.fill(n) {0.U}))//WireInit(0.U)//0.U //UInt()//WireInit(0.U)

  queue.io.brupdate := io.brupdate

  for(i <- 0 to n-1 by +1) {
    queue.io.enq(i).valid := in_valid(i)
    queue.io.enq(i).bits.uop := in_bitsUop(i)
    queue.io.enq(i).bits.data := in_bitsData(i)
    queue.io.enq(i).bits.predicated := io.in(0).bits.predicated
    queue.io.enq(i).bits.fflags := io.in(0).bits.fflags
  }



  queue.io.brupdate := io.brupdate
  queue.io.flush := io.kill

  io.out <> queue.io.deq // muss noch geaendert werden

//find oldest input signal
  for(i <- 0 to n-1 by +1) {
    when(io.in(i).valid){
      //when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldestValid.last).bits.uop.rob_idx, io.rob_head)){
      when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head)){
        //put old oldest into queue
        in_valid(oldest)      := io.in(oldest).valid
        in_bitsUop(oldest)    := io.in(oldest).bits.uop
        in_bitsData(oldest)   := io.in(oldest).bits.data
        //index of new oldest
        oldest := i.asUInt
      }
      when(IsOlder(io.in(oldest).bits.uop.rob_idx, io.in(i).bits.uop.rob_idx, io.rob_head)){
        //put i into queue
        in_valid(i)      := io.in(i).valid
        in_bitsUop(i)   := io.in(i).bits.uop
        in_bitsData(i)   := io.in(i).bits.data
      }
    }
    //for (i <- oldest+1 until io.in.length by +1) {
    //for (i <- oldest+1.U until n by +1) {
    //oldestValid :+ Mux(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head), i, oldest)

  }

  //compare oldest input signal to oldest of queue
  when(IsOlder(queue.io.deq.bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head)){
    qout := true.B
    //put old oldest into queue
    in_valid(oldest)      := io.in(oldest).valid
    in_bitsUop(oldest)    := io.in(oldest).bits.uop
    in_bitsData(oldest)   := io.in(oldest).bits.data
  }
  /*
  for (i <- 0 until count) {
    /*when(queue.valids(i)) {
      //when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldestValid.last).bits.uop.rob_idx, io.rob_head)){
      when(IsOlder(queue.io.enq.bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head)) {

      }
     }*/
   }*/

  //create sequence for grant
  when(!qout) {
    for (j <- 0 to n - 1 by +1) {
      if (j.asUInt == oldest) {
        //assert(returngrant(j) === true.B)
        returngrant(j) := true.B
      } else {
        //assert(returngrant(j) === false.B)
        returngrant(j) := false.B
      }
    }
  }
//###############################################################################





  //val i = 0
  //val oldest = io.in.length
  //val valids = io.in.map(_.valid)
  //val oldest = valids.indexWhere(x => {x==true.B})
  //find oldest valid input signal
  //val oldest = PriorityEncoder(io.in.map(_.valid))

  //if(oldest == -1){
  //  oldest = valids.indexWhere(x => {x==true.B})
  //}
  //while (i < to io.in.length && oldest == io.in.length)
  /**for (i <- 0 to io.in.length && oldest == io.in.length){
    if(io.in(i).valid){
      oldest = i
    }
    i=i+1
  }
*/

  //val returnseq = Seq.fill(n)(false.B)
  //val returnseq: Seq[Bool] = Seq.empty[Bool]
  //val oldest = 0.U
  //val oldest: Seq[ExeUnitResp] = Seq.empty[ExeUnitResp]
  //oldest :+ io.in(io.in.length-1)
  //find oldest in request
  //val oldestValid: Seq[UInt] = Seq.empty[UInt]
  //oldestValid :+ oldest
  //when(oldest <= i){
  /*for(i <- 0 to n-1 by +1) {
    when(io.in(i).valid){
      //when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldestValid.last).bits.uop.rob_idx, io.rob_head)){
      when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head)){
        oldest := i.asUInt
      }
    }

    //for (i <- oldest+1 until io.in.length by +1) {
    //for (i <- oldest+1.U until n by +1) {
      //oldestValid :+ Mux(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head), i, oldest)

  }
  for (j <- 0 to n-1 by +1){
    if(j.asUInt==oldest){
      //assert(returngrant(j) === true.B)
      returngrant(j) := true.B
    }else{
      //assert(returngrant(j) === false.B)
      returngrant(j) := false.B
    }
  }*/
  //else{
      //  oldestValid :+ oldest
      //}

  //val returnseq: Seq[Bool] = Seq.empty[Bool]
  //val returnseq = Seq.fill(io.in.length){false.B}
  //val returnseq: Seq[Bool] = Seq.fill(io.in.length)(false.B)
  //def returnseq: Seq[Bool]
  //returnseq.updated(oldest, true.B)
  //returnseq(oldest) = true.B
  //val returnseq: Seq[Bool] = Seq.fill(io.in.length)(false.B).updated(oldest, true.B)
  /**for(i <- 0 to n-1 by +1){
    if(i.U == oldest){
      returnseq :+ true.B
    }
    else{
      returnseq :+ false.B
    }
  }*/

  /**for(i <- 0 to n-1 by +1){
    i.U match {
      case oldest => returnseq :+ true.B
      case _ => returnseq :+ false.B
    }
  }*/



  val arbiterCtl = io.in.map(_.valid).length match{
    case 0 => Seq()
    case 1 => Seq(true.B)
    case _ => returngrant //returnseq
  }


  val grant = arbiterCtl
  for ((in, g) <- io.in.zip(grant)) {
    //set all in.ready to low
    in.ready := g && io.out.ready && false.B
  }
  //oldest is an input signal
  when(!qout){
    for ((in, g) <- io.in.zip(grant))
      in.ready := g && io.out.ready
    io.out.valid := !grant.last || io.in.last.valid
  }
  //oldest is output of queue
  when(qout){
    io.out.valid := queue.io.deq.valid //io.deq.bits.uop.valid
  }
}
