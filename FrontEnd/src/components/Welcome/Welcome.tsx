import { Title, Text } from '@mantine/core';
import classes from './Welcome.module.css';

export function Welcome() {
  return (
    <>
      <Title className={classes.title} ta="center" mt={100}>
        <Text inherit variant="gradient" component="span" gradient={{ from: 'pink', to: 'yellow' }}>
          Cypher
        </Text>
        Nod
      </Title>
      <Text c="dimmed" ta="center" size="lg" maw={580} mx="auto" mt="xl" dir="rtl">
       בוא נשמור על הקבצים שלך בטוחים!
      </Text>
    </>
  );
}
