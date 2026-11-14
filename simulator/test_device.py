import copy,json,tempfile,unittest
from pathlib import Path
from device import State,event,EXAMPLES,VALIDATORS
class ReplayTest(unittest.TestCase):
    def test_every_scenario_uses_the_unchanged_wire_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            state=State(Path(directory)/'test.sqlite')
            try:
                for scenario in ('guided','held','exit'):VALIDATORS['EventMessage'].validate(event(state,scenario))
            finally:state.close()
    def test_reliable_queue_and_sequence_survive_restart_and_rejected_ack_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test.sqlite';state=State(path);message=event(state,'guided');state.queue(message);before=state.pending();seq=message['device_seq'];state.close()
            state=State(path)
            try:
                self.assertGreater(state.seq(),seq);self.assertEqual(state.pending(),before)
                ack=copy.deepcopy(EXAMPLES['ack_stored']);ack.update(message_id=message['message_id'],status='REJECTED',error_code='VERSION_CONFLICT');state.acknowledge(ack)
                self.assertEqual(state.pending(),[]);self.assertEqual(state.db.execute('SELECT status,error FROM outbox').fetchone(),('REJECTED','VERSION_CONFLICT'))
            finally:state.close()
    def test_only_a_correlated_stored_or_duplicate_ack_removes_the_message(self):
        with tempfile.TemporaryDirectory() as directory:
            state=State(Path(directory)/'test.sqlite')
            try:
                message=event(state,'guided');state.queue(message);ack=copy.deepcopy(EXAMPLES['ack_stored']);ack['message_id']='unknown';state.acknowledge(ack);self.assertEqual(len(state.pending()),1)
                ack.update(message_id=message['message_id'],status='DUPLICATE');state.acknowledge(ack);self.assertEqual(state.pending(),[])
            finally:state.close()
if __name__=='__main__':unittest.main()
