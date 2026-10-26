from pathlib import Path
import argparse,subprocess,tempfile,json,hashlib
parser=argparse.ArgumentParser(description='Test RootOfTrust transfer source with HAL doubles; no Android build.')
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[6])
parser.add_argument('--report-dir',type=Path,default=None)
a=parser.parse_args();R=a.source.resolve();report_workspace=tempfile.TemporaryDirectory(prefix="warsaw-rot-report-");N=a.report_dir or Path(report_workspace.name);N.mkdir(parents=True,exist_ok=True)
p=R/'system/security/keystore2/src/shared_secret_negotiation.rs';s=p.read_text();region=s[s.index('/// Distinguish a verified transfer'):]
h=r'''
#![allow(non_snake_case)]
use std::any::Any;
use std::cell::RefCell;
use std::collections::VecDeque;
use std::time::Duration;
#[derive(Debug,Clone,Copy,PartialEq,Eq)] struct ErrorCode(i32);
impl ErrorCode {const ROOT_OF_TRUST_ALREADY_SET:Self=Self(-1);const UNIMPLEMENTED:Self=Self(-100);const SECURE_HW_COMMUNICATION_FAILED:Self=Self(-49);const HARDWARE_TYPE_UNAVAILABLE:Self=Self(-68);}
#[derive(Debug,Clone,PartialEq,Eq)] enum Error {Km(ErrorCode),Binder(i32)}
#[derive(Debug)] struct WrappedError {error:Error,contexts:Vec<String>}
impl WrappedError {fn root_cause(&self)->&dyn Any{&self.error}}
impl From<Error> for WrappedError{fn from(error:Error)->Self{Self{error,contexts:vec![]}}}
type Result<T,E=WrappedError>=std::result::Result<T,E>;
trait Context<T>{fn context(self,text:&str)->Result<T>;}
impl<T,E:Into<WrappedError>> Context<T> for Result<T,E>{fn context(self,text:&str)->Result<T>{self.map_err(|e|{let mut e=e.into();e.contexts.push(text.into());e})}}
#[derive(Debug,Clone,Copy,PartialEq,Eq)] struct SecurityLevel(i32);
impl SecurityLevel {const STRONGBOX:Self=Self(2);const TRUSTED_ENVIRONMENT:Self=Self(1);}
struct HardwareInfo{versionNumber:i32}
#[derive(Debug)] enum Action {
 Connect(SecurityLevel,Result<i32,Error>), Challenge(Result<[u8;16],Error>),
 Get([u8;16],Result<Vec<u8>,Error>), Send(Vec<u8>,Result<(),Error>), Wait(u64)
}
#[derive(Default)] struct Trace{actions:VecDeque<Action>,logs:Vec<String>}
thread_local!{static TRACE:RefCell<Trace>=RefCell::new(Trace::default());}
macro_rules! info{($($a:tt)*)=>{{TRACE.with(|t|t.borrow_mut().logs.push(format!("INFO {}",format!($($a)*))));}};}
macro_rules! error{($($a:tt)*)=>{{TRACE.with(|t|t.borrow_mut().logs.push(format!("ERROR {}",format!($($a)*))));}};}
macro_rules! warn{($($a:tt)*)=>{{TRACE.with(|t|t.borrow_mut().logs.push(format!("WARN {}",format!($($a)*))));}};}
fn next()->Action{TRACE.with(|t|t.borrow_mut().actions.pop_front().expect("unexpected call"))}
fn setup(actions:Vec<Action>){TRACE.with(|t|*t.borrow_mut()=Trace{actions:actions.into(),logs:vec![]});}
fn done(){TRACE.with(|t|assert!(t.borrow().actions.is_empty(),"unconsumed calls: {:?}",t.borrow().actions));}
fn map_km_error<T>(r:Result<T,Error>)->Result<T,Error>{r}
struct Device(SecurityLevel);
fn get_keymint_device(level:&SecurityLevel)->Result<(Device,HardwareInfo,())>{
 match next(){Action::Connect(expected,result)=>{assert_eq!(*level,expected);result.map(|versionNumber|(Device(*level),HardwareInfo{versionNumber},())).map_err(Into::into)},a=>panic!("expected connect, got {a:?}")}
}
impl Device{
 fn getRootOfTrustChallenge(&self)->Result<[u8;16],Error>{assert_eq!(self.0,SecurityLevel::STRONGBOX);match next(){Action::Challenge(r)=>r,a=>panic!("{a:?}")}}
 fn getRootOfTrust(&self,c:&[u8;16])->Result<Vec<u8>,Error>{assert_eq!(self.0,SecurityLevel::TRUSTED_ENVIRONMENT);match next(){Action::Get(expected,r)=>{assert_eq!(*c,expected);r},a=>panic!("{a:?}")}}
 fn sendRootOfTrust(&self,data:&[u8])->Result<(),Error>{assert_eq!(self.0,SecurityLevel::STRONGBOX);match next(){Action::Send(expected,r)=>{assert_eq!(data,expected);r},a=>panic!("{a:?}")}}
}
fn wait(d:Duration){match next(){Action::Wait(expected)=>assert_eq!(d,Duration::from_secs(expected)),a=>panic!("{a:?}")}}
fn km(code:i32)->Error{Error::Km(ErrorCode(code))}
fn connect()->Action{Action::Connect(SecurityLevel::STRONGBOX,Ok(200))}
fn successful(ch:u8)->Vec<Action>{vec![connect(),Action::Challenge(Ok([ch;16])),Action::Connect(SecurityLevel::TRUSTED_ENVIRONMENT,Ok(300)),Action::Get([ch;16],Ok(vec![0xd1,ch,0,255])),Action::Send(vec![0xd1,ch,0,255],Ok(()))]}
fn run()->Result<RootOfTrustTransfer>{let result=transfer_root_of_trust_with_retry(transfer_root_of_trust_once,wait);done();result}
__PRODUCTION__
#[test] fn success_forwards_exact_challenge_and_cbor(){setup(successful(37));assert_eq!(run().unwrap(),RootOfTrustTransfer::Transferred);}
#[test] fn absent_strongbox_does_not_contact_tee(){setup(vec![Action::Connect(SecurityLevel::STRONGBOX,Err(km(-68)))]);assert_eq!(run().unwrap(),RootOfTrustTransfer::NoStrongBox);}
#[test] fn legacy_keymint_does_not_call_v2_methods(){for version in [30,40,41,100]{setup(vec![Action::Connect(SecurityLevel::STRONGBOX,Ok(version))]);assert_eq!(run().unwrap(),RootOfTrustTransfer::NotSupported);}}
#[test] fn challenge_unimplemented_and_already_set_are_distinct(){for (code,expected) in [(-100,RootOfTrustTransfer::NotSupported),(-1,RootOfTrustTransfer::AlreadySet)]{setup(vec![connect(),Action::Challenge(Err(km(code)))]);assert_eq!(run().unwrap(),expected);}}
#[test] fn connection_errors_are_not_reported_as_absence(){setup(vec![Action::Connect(SecurityLevel::STRONGBOX,Err(Error::Binder(-32)))]);let e=run().unwrap_err();assert_eq!(e.error,Error::Binder(-32));assert_eq!(e.contexts.len(),1);}
#[test] fn challenge_transport_retry_uses_backoff(){let mut actions=vec![connect(),Action::Challenge(Err(km(-49))),Action::Wait(1),connect(),Action::Challenge(Err(km(-49))),Action::Wait(2)];actions.extend(successful(81));setup(actions);assert_eq!(run().unwrap(),RootOfTrustTransfer::Transferred);}
#[test] fn tee_transport_retry_requests_a_new_challenge(){let mut actions=vec![connect(),Action::Challenge(Ok([1;16])),Action::Connect(SecurityLevel::TRUSTED_ENVIRONMENT,Ok(300)),Action::Get([1;16],Err(km(-49))),Action::Wait(1)];actions.extend(successful(2));setup(actions);assert_eq!(run().unwrap(),RootOfTrustTransfer::Transferred);}
#[test] fn send_reply_loss_does_not_replay_old_challenge_or_payload(){let mut actions=successful(1);*actions.last_mut().unwrap()=Action::Send(vec![0xd1,1,0,255],Err(km(-49)));actions.push(Action::Wait(1));actions.extend(successful(2));setup(actions);assert_eq!(run().unwrap(),RootOfTrustTransfer::Transferred);}
#[test] fn mac_verification_failure_is_terminal(){let mut actions=successful(9);*actions.last_mut().unwrap()=Action::Send(vec![0xd1,9,0,255],Err(km(-30)));setup(actions);assert_eq!(run().unwrap_err().error,km(-30));}
#[test] fn unsupported_tee_does_not_become_optional_after_challenge(){setup(vec![connect(),Action::Challenge(Ok([1;16])),Action::Connect(SecurityLevel::TRUSTED_ENVIRONMENT,Ok(100)),Action::Get([1;16],Err(km(-100)))]);assert_eq!(run().unwrap_err().error,km(-100));}
#[test] fn missing_tee_is_not_missing_strongbox(){setup(vec![connect(),Action::Challenge(Ok([1;16])),Action::Connect(SecurityLevel::TRUSTED_ENVIRONMENT,Err(km(-68)))]);assert_eq!(run().unwrap_err().error,km(-68));}
#[test] fn retry_cap_and_terminal_error_preserve_root_cause(){let mut attempts=0;let mut delays=vec![];let result=transfer_root_of_trust_with_retry(||{attempts+=1;Err(WrappedError::from(km(if attempts<=9{-49}else{-30})))},|d|delays.push(d.as_secs()));assert_eq!(result.unwrap_err().error,km(-30));assert_eq!(delays,[1,2,4,8,16,30,30,30,30]);assert_eq!(attempts,10);}
#[test] fn only_communication_failure_is_retried(){for code in -1001..=1{let mut count=0;let mut waits=0;let r=transfer_root_of_trust_with_retry(||{count+=1;Err(WrappedError::from(km(if count==1{code}else{-1000})))},|_|waits+=1);assert_eq!(waits,usize::from(code==-49));assert_eq!(r.unwrap_err().error,km(if code==-49{-1000}else{code}));}}
#[test] fn final_logs_do_not_claim_failed_delivery_was_acknowledged(){let mut actions=successful(1);*actions.last_mut().unwrap()=Action::Send(vec![0xd1,1,0,255],Err(km(-30)));setup(actions);transfer_root_of_trust();done();TRACE.with(|t|{let t=t.borrow();assert!(t.logs.iter().any(|l|l.starts_with("ERROR RootOfTrust transfer failed")));assert!(!t.logs.iter().any(|l|l.contains("acknowledged")));});setup(successful(2));transfer_root_of_trust();done();TRACE.with(|t|assert!(t.borrow().logs.iter().any(|l|l=="INFO RootOfTrust transfer acknowledged by StrongBox")));}
'''.replace('__PRODUCTION__',region)
with tempfile.TemporaryDirectory(prefix='warsaw-rot-tests-') as tmp:
 t=Path(tmp);(t/'test.rs').write_text(h)
 result=subprocess.run([str(R/'prebuilts/rust-toolchain/linux-x86/1.93.1/bin/rustc'),'--edition=2021','--test',str(t/'test.rs'),'-o',str(t/'test')],capture_output=True,text=True)
 print(result.stderr);result.check_returncode()
 result=subprocess.run([str(t/'test')],capture_output=True,text=True);print(result.stdout,result.stderr);result.check_returncode()
 (N/'root-of-trust-tests.txt').write_text(result.stdout)
 assert s.index('return;',s.index('if let Err(e) = negotiate_shared_secret')) < s.index('transfer_root_of_trust();')
 (N/'root-of-trust-validation.json').write_text(json.dumps({'host_tests':14,'retry_classification_values':1003,'production_functions_compiled_with_hal_doubles':True,'failed_shared_secret_stops_before_transfer':True,'new_challenge_after_ambiguous_send_result':True,'cbor_forwarded_unchanged':True,'full_android_build':False,'runtime_acknowledgement_verified':False,'source_sha256':hashlib.sha256(p.read_bytes()).hexdigest()},indent=2)+'\n')
